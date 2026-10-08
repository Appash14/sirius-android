"""Sous-ensemble du protocole voix réel, sans STT, TTS ni accès externe.

API voix HTTPS : 4860. Commandes de test HTTP locales : 4861.
hello est envoyé par le serveur ; ready reçu du téléphone prouve son traitement.
"""

import argparse
import asyncio
import base64
import json
import ssl
import time
import io
import wave
import array
import math
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from aiohttp import WSMsgType, web

ROOT = Path(__file__).resolve().parents[1]
AUTH = "Basic " + base64.b64encode(b"e2e:e2e").decode()


@dataclass
class Client:
    epoch: int = 0
    messages: list = field(default_factory=list)
    sockets: dict = field(default_factory=dict)
    audio: dict = field(default_factory=dict)
    acks: set = field(default_factory=set)
    hello_sent: int = 0
    hello_received: int = 0
    disconnected_at: float = 0
    blocked_until: float = 0
    uploads: dict = field(default_factory=dict)
    chunks: list = field(default_factory=list)
    actions: dict = field(default_factory=dict)
    pending: dict | None = None
    interrupts: list = field(default_factory=list)
    wake_rejected: bool = False


class VoiceServer:
    def __init__(self, events=None):
        self.clients = {}
        self.tasks = set()
        self.public_site = None
        self.scenario = None
        self.target = None
        self.cursor = 0
        self.events = events
        self.mp3 = base64.b64encode((ROOT / "audio/test.mp3").read_bytes()).decode()

    def state(self, client):
        return self.clients.setdefault(client, Client())

    def log(self, event, **data):
        row = {"time": time.time(), "event": event, **data}
        if self.events:
            with self.events.open("a", encoding="utf-8") as output:
                output.write(json.dumps(row, ensure_ascii=False) + "\n")
        print(json.dumps(row, ensure_ascii=False), flush=True)

    def snapshot(self):
        return {
            "scenario": self.scenario["name"] if self.scenario else None,
            "cursor": self.cursor,
            "clients": {key: {
                "hello_sent": c.hello_sent, "hello_received": c.hello_received,
                "ready": any(c.sockets.values()), "sockets": len(c.sockets),
                "messages": len(c.messages), "audio_ack": len(c.acks),
                "epoch": c.epoch,
                "history": c.messages, "chunks": c.chunks, "actions": c.actions, "interrupts": c.interrupts,
                "wake_rejected": c.wake_rejected,
            } for key, c in self.clients.items()},
        }

    async def emit(self, client, event):
        event = {"client": client, "epoch": self.state(client).epoch, **event}
        if event["type"] == "message":
            self.state(client).messages.append(event)
        for ws in list(self.state(client).sockets):
            if not ws.closed:
                await ws.send_json(event)
        self.log("send", client=client, type=event["type"], id=event.get("id"))
        return event

    async def resume(self, ws, client):
        c = self.state(client)
        for key, events in list(c.audio.items()):
            if key in c.acks:
                continue
            for event in events:
                await ws.send_json(event)
            self.log("audio_replay", client=client, id=key[0], index=key[2])

    async def websocket(self, request):
        client = request.query.get("client", "")[:80]
        c = self.state(client)
        if time.monotonic() < c.blocked_until:
            raise web.HTTPServiceUnavailable(text="Coupure réseau simulée")
        if c.disconnected_at and time.monotonic() - c.disconnected_at >= 60:
            c.audio.clear()
        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=2048)
        await ws.prepare(request)
        c.sockets[ws] = False
        c.disconnected_at = 0
        if self.scenario and self.target == client:
            await asyncio.sleep(self.scenario.get("hello_delay", 0))
        await ws.send_json({
            "type": "hello", "client": client, "time": time.time(),
            "epoch": c.epoch, "messages": c.messages[-200:],
            "capabilities": {"client_routing": True, "reply_to": True,
                "client_interrupt": True, "partiels": True, "fin_de_tour": False,
                "audio_resume": "acknowledged_sentences", "phone_actions": True},
            "audio_resume": {"status": "pending" if c.audio else "none",
                "replay": bool(c.audio), "retention_s": 60},
        })
        c.hello_sent += 1
        self.log("hello_sent", client=client, count=c.hello_sent)
        hello_confirmed = False
        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                data = json.loads(msg.data)
                kind = data.get("type")
                self.log("receive", client=client, type=kind, id=data.get("id"))
                if kind == "ready":
                    c.sockets[ws] = data.get("ready") is True
                    if c.sockets[ws]:
                        if not hello_confirmed:
                            c.hello_received += 1
                            hello_confirmed = True
                            self.log("hello_received", client=client, count=c.hello_received)
                        await self.resume(ws, client)
                elif kind == "audio_ack":
                    key = (data.get("id"), data.get("epoch"), data.get("index"))
                    if key in c.audio:
                        c.acks.add(key)
                        c.audio.pop(key)
                        self.log("audio_ack", client=client, id=key[0], index=key[2])
                elif kind == "interrupt":
                    c.interrupts.append(time.time())
                    c.epoch += 1
                    c.audio.clear()
                    await self.emit(client, {"type": "interrupted", "reason": "interrupt", "reply_to": None})
                elif kind == "action_result":
                    c.actions[data["id"]] = data
                    self.log("action_result", client=client, **data)
        finally:
            c.sockets.pop(ws, None)
            if not c.sockets:
                c.disconnected_at = time.monotonic()
            self.log("disconnect", client=client)
        return ws

    async def turn(self, client, tom, sirius, enonce, question, audio_name="test.mp3", delay_s=0, legacy=False):
        c = self.state(client)
        now = datetime.now(timezone.utc).isoformat()
        await self.emit(client, {"type": "message", "id": question, "role": "tom", "text": tom,
            "enonce": f"e{len(c.uploads)}" if legacy else enonce, "at": now, "delivery": "pending", "reply_to": None, "verrouille": False, "cancelled": False})
        await self.emit(client, {"type": "delivery", "id": question, "status": "sent", "at": now, "reply_to": question})
        await self.emit(client, {"type": "thinking", "id": question, "reply_to": question})
        if delay_s:
            async def delayed():
                await asyncio.sleep(2)
                await self.answer(client, question, "Je regarde.", "reponse.mp3", "relance-" + question)
                await asyncio.sleep(8)
                await self.emit(client, {"type": "ignored", "reply_to": None})
                await asyncio.sleep(max(0, delay_s - 10))
                await self.answer(client, question, sirius, audio_name)
            task = asyncio.create_task(delayed())
            self.tasks.add(task)
            task.add_done_callback(self.tasks.discard)
        else:
            await self.answer(client, question, sirius, audio_name)

    async def answer(self, client, question, sirius, audio_name, reply_id=None):
        c = self.state(client)
        now = datetime.now(timezone.utc).isoformat()
        reply = reply_id or question + "-reponse"
        extra = {"relance": True} if reply_id else {}
        if not reply_id:
            await self.emit(client, {"type": "message", "id": reply, "role": "sirius", "text": sirius,
                "at": now, "reply_to": question, "verrouille": False})
        start = await self.emit(client, {"type": "reply_start", "id": reply, "reply_to": question, **extra})
        audio = {"client": client, "epoch": c.epoch, "type": "audio", "id": reply,
            "reply_to": question, **extra, "index": 0, "text": sirius, "mime": "audio/mpeg",
            "audio": self.mp3 if audio_name == "test.mp3" else base64.b64encode((ROOT / "audio" / audio_name).read_bytes()).decode()}
        end = {"client": client, "epoch": c.epoch, "type": "reply_end", "id": reply, "reply_to": question, **extra}
        c.audio[(reply, c.epoch, 0)] = [start, audio, end]
        for ws, ready in list(c.sockets.items()):
            if ready and not ws.closed:
                await ws.send_json(audio)
                await ws.send_json(end)
        self.log("audio_sent", client=client, id=reply)

    async def health(self, request):
        return web.json_response({"ok": True, "listeners": sum(len(c.sockets) for c in self.clients.values())})

    async def history(self, request):
        return web.json_response({"messages": self.state(request.query.get("client", "")).messages[-200:]})

    async def phrase(self, request):
        form = await request.post()
        client = form.get("client", "")
        c = self.state(client)
        enonce = form.get("enonce", "phrase-unique")
        seq = int(form.get("seq", 0))
        question = f"voix-upload-{enonce}"
        done = enonce in c.uploads
        if done:
            return web.json_response({"accepted": True, "id": question, "fin_de_tour": True}, status=202)
        audio = form.get("audio")
        eveil = request.query.get("eveil", "").lower() == "true" or request.headers.get("X-Sirius-Eveil", "").lower() == "true"
        evidence = {"enonce": enonce, "seq": seq, "final": form.get("fin", "1") == "1", "bytes": 0, "eveil": eveil}
        if audio is not None:
            raw = audio.file.read()
            with wave.open(io.BytesIO(raw)) as wav:
                if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                    raise web.HTTPBadRequest(text="WAV mono PCM16 16 kHz attendu")
                pcm = array.array("h", wav.readframes(wav.getnframes()))
            evidence.update(bytes=len(raw), rms=math.sqrt(sum(x*x for x in pcm) / max(1, len(pcm))))
        c.chunks.append(evidence)
        self.log("microphone_chunk", client=client, **evidence)
        if eveil and self.scenario and self.scenario.get("reject_wake"):
            c.wake_rejected = True
            await self.emit(client, {"type": "reveil_rejete"})
            return web.json_response({"accepted": True, "id": question}, status=202)
        pending = c.pending
        if form.get("fin", "1") == "1":
            c.uploads[enonce] = question
            c.pending = None
            await self.turn(client, pending["tom"] if pending else "Phrase reçue du microphone simulé.",
                pending["sirius"] if pending else "Audio de test reçu.", enonce, question,
                pending.get("audio", "test.mp3") if pending else "test.mp3",
                pending.get("delay_s", 0) if pending else 0, pending.get("legacy", False) if pending else False)
            result = {"accepted": True, "id": question}
        else:
            partials = pending.get("partials", []) if pending else []
            text = partials[min(seq, len(partials)-1)] if partials else (pending["tom"][:80] if pending else "Phrase en cours")
            await self.emit(client, {"type": "partiel", "enonce": enonce, "text": text, "reply_to": None})
            result = {"accepted": True, "enonce": enonce, "seq": seq}
        self.log("phrase", client=client, enonce=enonce, seq=seq)
        return web.json_response(result, status=202)

    async def text(self, request):
        data = await request.json()
        client = data.get("client", "")
        question = f"voix-texte-{len(self.state(client).messages)}"
        await self.turn(client, data["texte"], "Réponse du faux serveur.", question, question)
        return web.json_response({"accepted": True, "id": question}, status=202)

    async def retry(self, request):
        data = await request.json()
        c = self.state(data.get("client", ""))
        if not any(m["id"] == data.get("id") and m.get("delivery") == "failed" for m in c.messages):
            raise web.HTTPNotFound()
        return web.json_response({"accepted": True, "id": data["id"]}, status=202)

    async def status(self, request):
        return web.json_response(self.snapshot())

    async def start(self, request):
        data = await request.json()
        name = data["file"]
        if Path(name).name != name or not name.endswith(".json"):
            raise web.HTTPBadRequest(text="Nom de scénario invalide")
        self.scenario = json.loads((ROOT / "scenarios" / name).read_text())
        self.target = data["client"]
        self.cursor = 0
        c = self.state(self.target)
        for task in list(self.tasks):
            task.cancel()
        c.messages.clear()
        c.audio.clear()
        c.acks.clear()
        c.uploads.clear()
        c.chunks.clear()
        c.actions.clear()
        c.interrupts.clear()
        c.wake_rejected = False
        c.pending = None
        if self.scenario.get("early_speech"):
            c.pending = self.scenario["steps"][0]
            self.cursor = 1
        self.log("scenario_start", name=self.scenario["name"], client=self.target)
        return web.json_response(self.snapshot())

    async def step(self, request):
        if not self.scenario or self.cursor >= len(self.scenario["steps"]):
            raise web.HTTPConflict(text="Aucune étape disponible")
        step = self.scenario["steps"][self.cursor]
        c = self.state(self.target)
        if not any(c.sockets.values()):
            raise web.HTTPConflict(text="Téléphone pas prêt")
        if step["type"] == "turn":
            if step.get("microphone"):
                c.pending = step
            else:
                ident = f"voix-{self.scenario['name']}-{self.cursor:02d}"
                await self.turn(self.target, step["tom"], step["sirius"], ident, ident)
        elif step["type"] == "action":
            await self.emit(self.target, {"type": "action", "id": step["id"], "action": step["action"], "args": step["args"]})
        elif step["type"] == "disconnect":
            c.blocked_until = time.monotonic() + step.get("seconds", 5)
            for ws in list(c.sockets):
                await ws.close(code=1000, message=b"e2e server shutdown")
            if step.get("restart") and self.public_site:
                await self.public_site.stop()
                # The real process reloads journal history, but no in-memory audio or STT uploads.
                c.audio.clear()
                c.uploads.clear()
                c.pending = None
                self.log("server_stopped", seconds=step.get("seconds", 5))
                async def restart():
                    await asyncio.sleep(step.get("seconds", 5))
                    await self.public_site.start()
                    self.log("server_restarted")
                task = asyncio.create_task(restart())
                self.tasks.add(task)
                task.add_done_callback(self.tasks.discard)
        else:
            raise web.HTTPBadRequest(text="Type d'étape inconnu")
        self.cursor += 1
        self.log("step", scenario=self.scenario["name"], index=self.cursor, type=step["type"])
        return web.json_response({"step": step, **self.snapshot()})

    def public_app(self):
        @web.middleware
        async def authenticate(request, handler):
            if request.headers.get("Authorization") != AUTH:
                raise web.HTTPUnauthorized(headers={"WWW-Authenticate": 'Basic realm="Sirius E2E"'})
            return await handler(request)

        app = web.Application(middlewares=[authenticate], client_max_size=4 * 1024 * 1024)
        for prefix in ("", "/voix", "/voix/tg"):
            app.router.add_get(prefix + "/api/sante", self.health)
            app.router.add_get(prefix + "/api/historique", self.history)
            app.router.add_post(prefix + "/api/phrase", self.phrase)
            app.router.add_post(prefix + "/api/texte", self.text)
            app.router.add_post(prefix + "/api/reessayer", self.retry)
            app.router.add_get(prefix + "/ws", self.websocket)
        async def stop_tasks(app):
            for task in list(self.tasks):
                task.cancel()
            if self.tasks:
                await asyncio.gather(*self.tasks, return_exceptions=True)
        app.on_cleanup.append(stop_tasks)
        return app

    def admin_app(self):
        app = web.Application()
        app.router.add_get("/status", self.status)
        app.router.add_post("/scenario", self.start)
        app.router.add_post("/step", self.step)
        return app


async def main(args):
    server = VoiceServer(Path(args.events))
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(args.cert, args.key)
    runners = []
    for app, host, port, context in (
        (server.public_app(), "0.0.0.0", args.port, tls),
        (server.admin_app(), "127.0.0.1", args.admin_port, None),
    ):
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, host, port, ssl_context=context)
        await site.start()
        if context:
            server.public_site = site
        runners.append(runner)
    server.log("started", port=args.port)
    try:
        await asyncio.Event().wait()
    finally:
        for runner in runners:
            await runner.cleanup()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--events", required=True)
    parser.add_argument("--port", type=int, default=4860)
    parser.add_argument("--admin-port", type=int, default=4861)
    asyncio.run(main(parser.parse_args()))
