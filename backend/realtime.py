import asyncio
import time

from fastapi import WebSocket
from database import conexion
from starlette.concurrency import run_in_threadpool

def propietarios_de_cuentas(cuentas: set[int]):
    if not cuentas:
        return {}

    ids = sorted(cuentas)
    marcadores = ",".join("?" for _ in ids)

    with conexion() as db:
        filas = db.execute(
            f"""
            SELECT id, usuario_id
            FROM cuentas
            WHERE id IN ({marcadores})
            """,
            ids,
        ).fetchall()

    propietarios = {}

    for fila in filas:
        propietarios.setdefault(fila["usuario_id"], []).append(
            fila["id"]
        )

    return propietarios

class GestorConexiones:
    def __init__(self):
        self.conexiones = {}

    def registrar(self, websocket: WebSocket, sesion: dict):
        self.conexiones[websocket] = {
            "usuario_id": sesion["usuario_id"],
            "token_hash": sesion["token_hash"],
            "expira": sesion["expira"],
            "lock": asyncio.Lock(),
        }

    def eliminar(self, websocket: WebSocket):
        self.conexiones.pop(websocket, None)

    async def cerrar(self, websocket: WebSocket):
        self.eliminar(websocket)

        try:
            await asyncio.wait_for(
                websocket.close(code=1008),
                timeout=2,
            )
        except Exception:
            pass

    async def enviar(self, websocket: WebSocket, mensaje: dict):
        info = self.conexiones.get(websocket)

        if info is None:
            return

        if info["expira"] <= int(time.time()):
            await self.cerrar(websocket)
            return

        try:
            # Evita escrituras simultáneas sobre el mismo WebSocket.
            async with info["lock"]:
                await asyncio.wait_for(
                    websocket.send_json(mensaje),
                    timeout=2,
                )
        except Exception:
            await self.cerrar(websocket)

    async def notificar(self, cuentas: set[int], operacion_id: str):
        propietarios = await run_in_threadpool(
            propietarios_de_cuentas,
            cuentas,
        )

        envios = []

        for websocket, info in list(self.conexiones.items()):
            cuentas_propias = propietarios.get(info["usuario_id"])

            if cuentas_propias:
                mensaje = {
                    "evento": "cuenta_actualizada",
                    "operacion_id": operacion_id,
                    "cuentas_ids": cuentas_propias,
                }

                envios.append(
                    self.enviar(websocket, mensaje)
                )

        await asyncio.gather(
            *envios,
            return_exceptions=True,
        )

    async def cerrar_sesion(self, token_hash: str):
        destinatarios = [
            websocket
            for websocket, info in list(self.conexiones.items())
            if info["token_hash"] == token_hash
        ]

        await asyncio.gather(
            *(self.cerrar(ws) for ws in destinatarios),
            return_exceptions=True,
        )


gestor = GestorConexiones()