import asyncio, json, os, sys, tempfile, importlib.machinery, importlib.util, hashlib
from pathlib import Path
os.environ['VOIX_FLUX']='0'
os.environ['VOIX_RELANCE']='0'
source=Path(sys.argv[1]).resolve()
root=source.parent
sys.path[:0]=[str(root),str(root/'tests')]
loader=importlib.machinery.SourceFileLoader('serveur',str(source))
spec=importlib.util.spec_from_loader('serveur',loader)
module=importlib.util.module_from_spec(spec);sys.modules['serveur']=module;loader.exec_module(module)
from test_partiels import Direct, audio
async def run():
 test=Direct('test_texte_en_direct_puis_final_complet_sans_fenetre_de_regroupement')
 await test.asyncSetUp()
 try:
  ws=await test.navigateur('appareil-test')
  partials=[]
  for seq in range(3):
   response=await test.bout('telephone-uuid',seq,audio(seq+1),parole=900,client='appareil-test')
   assert response.status==202
   partials.extend(await test.evenements(ws,'partiel'))
  response=await test.bout('telephone-uuid',3,fin=True,client='appareil-test')
  post=await response.json()
  final=(await test.evenements(ws,'message'))[0]
  await test.attendre(lambda: test.finaux())
  await ws.close()
  replay=await test.client.ws_connect('/voix/ws?client=appareil-test')
  test.sockets.append(replay)
  hello=await replay.receive_json()
  data={'source':source.name,'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'partials':partials,'post_final':post,'message':final,'history':hello['messages'],'sirius_input':test.finaux()}
  Path(sys.argv[2]).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
 finally:
  await test.asyncTearDown()
asyncio.run(run())
