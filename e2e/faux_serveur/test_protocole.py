"""Tests du contrat réseau : isolement, accusés audio et reprise après coupure."""

import asyncio
import json
import unittest
import io
import wave
import array

from aiohttp import FormData
from aiohttp.test_utils import TestClient, TestServer

from e2e.faux_serveur.serveur import AUTH, ROOT, VoiceServer


class ProtocolTest(unittest.IsolatedAsyncioTestCase):
    async def test_wake_flag_query_and_header_reach_state_and_rejection_is_silent(self):
        for suffix, headers in (("?eveil=true", {}), ("", {"X-Sirius-Eveil": "true"})):
            self.voice.scenario = {"name": "reveil_rejete", "reject_wake": True, "steps": []}
            ws, _ = await self.connect("reveil")
            data = FormData()
            data.add_field("client", "reveil")
            data.add_field("enonce", "wake" + str(len(self.voice.state("reveil").chunks)))
            data.add_field("seq", "0"); data.add_field("fin", "1")
            response = await self.http.post("/voix/api/phrase" + suffix, data=data, headers=headers)
            self.assertEqual(response.status, 202)
            event = await ws.receive_json(timeout=2)
            self.assertEqual(event["type"], "reveil_rejete")
            state = self.voice.snapshot()["clients"]["reveil"]
            self.assertTrue(state["chunks"][-1]["eveil"])
            self.assertTrue(state["wake_rejected"])
            self.assertEqual(state["history"], [])
            self.assertEqual(state["audio_ack"], 0)
            self.assertFalse(self.voice.state("reveil").audio)
            await ws.close()

    async def asyncSetUp(self):
        self.voice = VoiceServer()
        self.http = TestClient(TestServer(self.voice.public_app()), headers={"Authorization": AUTH})
        self.admin = TestClient(TestServer(self.voice.admin_app()))
        await self.http.start_server()
        await self.admin.start_server()

    async def asyncTearDown(self):
        await self.http.close()
        await self.admin.close()

    async def connect(self, client, prefix="/voix"):
        ws = await self.http.ws_connect(f"{prefix}/ws?client={client}")
        hello = await ws.receive_json(timeout=2)
        self.assertEqual(hello["type"], "hello")
        self.assertEqual(hello["client"], client)
        await ws.send_json({"type": "ready", "ready": True})
        await asyncio.sleep(.02)
        return ws, hello

    async def frames(self, ws, last="reply_end"):
        result = []
        while True:
            event = await ws.receive_json(timeout=2)
            result.append(event)
            if event["type"] == last:
                return result

    async def test_ack_is_scoped_to_client_and_survives_reconnection(self):
        ws, _ = await self.connect("tom")
        other, _ = await self.connect("autre", "")
        response = await self.http.post("/voix/api/texte", json={"client": "tom", "texte": "Bonjour"})
        self.assertEqual(response.status, 202)
        events = await self.frames(ws)
        self.assertEqual([x["role"] for x in events if x["type"] == "message"], ["tom", "sirius"])
        audio = next(x for x in events if x["type"] == "audio")
        self.assertEqual(audio["mime"], "audio/mpeg")
        ack = {"type": "audio_ack", **{key: audio[key] for key in ("id", "epoch", "index")}}
        await other.send_json(ack)
        await asyncio.sleep(.02)
        self.assertEqual(len(self.voice.state("tom").audio), 1)
        self.assertEqual(self.voice.state("autre").messages, [])
        await ws.close()
        ws, hello = await self.connect("tom")
        self.assertEqual(hello["audio_resume"]["status"], "pending")
        replay = await self.frames(ws)
        self.assertEqual(next(x for x in replay if x["type"] == "audio"), audio)
        await ws.send_json(ack)
        await asyncio.sleep(.02)
        self.assertFalse(self.voice.state("tom").audio)
        await ws.close()
        ws, hello = await self.connect("tom")
        self.assertFalse(hello["audio_resume"]["replay"])
        self.assertEqual(len(hello["messages"]), 2)
        self.assertEqual(self.voice.state("tom").hello_received, 3)
        await ws.close()
        await other.close()

    async def test_network_outage_rejects_ws_then_keeps_history_and_epoch(self):
        ws, _ = await self.connect("tom")
        await self.admin.post("/scenario", json={"client": "tom", "file": "03-reconnexion.json"})
        for step in self.voice.scenario["steps"]:
            step["microphone"] = False
        await self.admin.post("/step", json={})
        await self.frames(ws)
        response = await self.admin.post("/step", json={})
        self.assertEqual(response.status, 200)
        response = await self.http.get("/voix/ws?client=tom")
        self.assertEqual(response.status, 503)
        self.voice.state("tom").blocked_until = 0
        ws, hello = await self.connect("tom")
        self.assertEqual(hello["epoch"], 0)
        self.assertEqual(len(hello["messages"]), 2)
        await self.frames(ws)  # Rejeu non accusé.
        response = await self.admin.post("/step", json={})
        self.assertEqual(response.status, 200)
        await self.frames(ws)
        self.assertEqual(self.voice.cursor, 3)
        await ws.close()

    async def test_alias_auth_and_final_chunk_deduplication(self):
        response = await self.http.get("/api/sante", headers={"Authorization": ""})
        self.assertEqual(response.status, 401)
        for prefix in ("", "/voix", "/voix/tg"):
            response = await self.http.get(prefix + "/api/sante")
            self.assertEqual(response.status, 200)
        ws, _ = await self.connect("tom", "/voix/tg")
        data = FormData({"client": "tom", "enonce": "abcd", "seq": "0", "fin": "1", "parole": "0"})
        response = await self.http.post("/voix/api/phrase", data=data)
        self.assertEqual(response.status, 202)
        events = await self.frames(ws)
        self.assertNotIn("fin_de_tour", [x["type"] for x in events])
        self.assertEqual(events[0]["id"], (await response.json())["id"])
        data = FormData({"client": "tom", "enonce": "abcd", "seq": "0", "fin": "1", "parole": "0"})
        response = await self.http.post("/voix/api/phrase", data=data)
        self.assertTrue((await response.json())["fin_de_tour"])
        self.assertEqual(len(self.voice.state("tom").messages), 2)
        await ws.send_json({"type": "interrupt"})
        event = await ws.receive_json(timeout=2)
        self.assertEqual((event["type"], event["epoch"]), ("interrupted", 1))
        self.assertFalse(self.voice.state("tom").audio)
        await ws.close()

    async def test_real_wav_chunks_return_partials_before_one_final_and_preserve_energy(self):
        ws, _ = await self.connect("tom")
        await self.admin.post("/scenario", json={"client": "tom", "file": "05-partiels.json"})
        self.voice.scenario["steps"][0]["audio"] = "test.mp3"
        self.assertEqual(self.voice.cursor, 1)
        wav = io.BytesIO()
        with wave.open(wav, "wb") as audio:
            audio.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            audio.writeframes(array.array("h", [1600, -1600] * 8000).tobytes())
        for seq, text in enumerate(self.voice.scenario["steps"][0]["partials"]):
            data = FormData({"client": "tom", "enonce": "microphone-1", "seq": str(seq), "fin": "0", "parole": "1000"})
            data.add_field("audio", wav.getvalue(), filename="bout.wav", content_type="audio/wav")
            response = await self.http.post("/voix/api/phrase", data=data)
            self.assertEqual(response.status, 202)
            partial = await ws.receive_json(timeout=2)
            self.assertEqual((partial["type"], partial["text"], partial["enonce"]), ("partiel", text, "microphone-1"))
            self.assertFalse(self.voice.state("tom").messages)
        data = FormData({"client": "tom", "enonce": "microphone-1", "seq": "2", "fin": "1", "parole": "0"})
        response = await self.http.post("/voix/api/phrase", data=data)
        self.assertEqual(response.status, 202)
        await self.frames(ws)
        self.assertEqual(len(self.voice.state("tom").messages), 2)
        self.assertEqual(self.voice.state("tom").chunks[0]["rms"], 1600)
        self.assertIsNone(self.voice.state("tom").pending)
        await ws.close()

    async def test_action_results_are_routed_to_the_authenticated_client(self):
        ws, _ = await self.connect("tom")
        other, _ = await self.connect("autre")
        await self.admin.post("/scenario", json={"client": "tom", "file": "07-pilotage.json"})
        response = await self.admin.post("/step", json={})
        self.assertEqual(response.status, 200)
        command = await ws.receive_json(timeout=2)
        self.assertEqual(command["action"], "ouvrir")
        await other.send_json({"type": "action_result", "id": command["id"], "ok": True, "data": {}})
        await asyncio.sleep(.02)
        self.assertFalse(self.voice.state("tom").actions)
        await ws.send_json({"type": "action_result", "id": command["id"], "ok": True, "data": {}})
        await asyncio.sleep(.02)
        self.assertTrue(self.voice.state("tom").actions[command["id"]]["ok"])
        await ws.close()
        await other.close()

    async def test_final_identifier_contract_matches_traces_from_both_real_servers(self):
        for legacy, fixture in ((False, "serveur-actuel.json"), (True, "serveur-avant-doublon.json")):
            trace = json.loads((ROOT / "contrat" / fixture).read_text())
            self.assertEqual(trace["message"]["id"], trace["post_final"]["id"])
            phone_id = trace["partials"][0]["enonce"]
            if legacy:
                self.assertNotEqual(phone_id, trace["message"]["enonce"])
            else:
                self.assertEqual(phone_id, trace["message"]["enonce"])
            client_id = "contract-" + str(legacy)
            ws, _ = await self.connect(client_id)
            await self.voice.turn(client_id, "Phrase", "Réponse", phone_id, "voix-question", legacy=legacy)
            frames = await self.frames(ws)
            final = next(f for f in frames if f["type"] == "message" and f["role"] == "tom")
            self.assertEqual(final["id"], "voix-question")
            self.assertEqual(final["enonce"] == phone_id, not legacy)
            self.assertEqual(final["delivery"], trace["message"]["delivery"])
            self.assertEqual(final["cancelled"], trace["message"]["cancelled"])
            self.assertFalse(any(f["type"] == "fin_de_tour" for f in frames))
            await ws.close()

    def test_four_scenarios_have_the_requested_coverage(self):
        scenarios = [json.loads(path.read_text()) for path in sorted((ROOT / "scenarios").glob("*.json"))]
        by_name = {scenario["name"]: scenario for scenario in scenarios}
        self.assertEqual(len(by_name), len(scenarios), "Noms de scénarios uniques pour les vidéos")
        self.assertTrue({"conversation_simple", "texte_long", "coupure_reconnexion", "dix_echanges"} <= by_name.keys())
        self.assertEqual(len(by_name["dix_echanges"]["steps"]), 10)
        self.assertGreater(len(by_name["texte_long"]["steps"][0]["tom"]), 1000)
        self.assertGreater(len(by_name["texte_long"]["steps"][0]["sirius"]), 2000)
        self.assertEqual(by_name["coupure_reconnexion"]["steps"][1]["type"], "disconnect")
        self.assertEqual(len(scenarios), 9)
        self.assertTrue(by_name["transcript_direct"]["steps"][0]["partials"])
        self.assertTrue(by_name["surimpression"]["overlay"])
        self.assertFalse(by_name["pilotage"]["steps"][-1]["ok"])


if __name__ == "__main__":
    unittest.main()
