import asyncio
import time

from fastapi import WebSocket


class GestorConexiones:
    def __init__(self):
        self.conexiones = {}

    def registrar(self, websocket: WebSocket, sesion: dict):
        self.conexiones[websocket] = {
            "cuenta_id": sesion["cuenta_id"],
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
        mensaje = {
            "evento": "cuenta_actualizada",
            "operacion_id": operacion_id,
        }

        destinatarios = [
            websocket
            for websocket, info in list(self.conexiones.items())
            if info["cuenta_id"] in cuentas
        ]

        await asyncio.gather(
            *(self.enviar(ws, mensaje) for ws in destinatarios),
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