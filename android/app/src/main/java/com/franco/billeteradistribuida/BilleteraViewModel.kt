package com.franco.billeteradistribuida

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class OrdenPendiente(val ruta: String, val cuerpo: String, val titulo: String)

class BilleteraViewModel : ViewModel() {
    private val api = BilleteraApi()
    private var token: String? = null
    private var socket: WebSocket? = null
    private var reconexion: Job? = null
    private var refrescoPendiente = false
    var cuenta by mutableStateOf<Cuenta?>(null)
        private set
    var cuentas by mutableStateOf<List<Cuenta>>(emptyList())
        private set
    var perfil by mutableStateOf<Perfil?>(null)
        private set
    var servicios by mutableStateOf<List<Servicio>>(emptyList())
        private set
    var movimientos by mutableStateOf<List<Movimiento>>(emptyList())
        private set
    var siguiente by mutableStateOf<Long?>(null)
        private set
    var resumen by mutableStateOf<Resumen?>(null)
        private set
    var comprobante by mutableStateOf<Movimiento?>(null)
        private set
    var beneficiario by mutableStateOf<Beneficiario?>(null)
        private set
    private var tipoValidado = ""
    var pendiente by mutableStateOf<OrdenPendiente?>(null)
        private set
    var resultado by mutableStateOf<String?>(null)
        private set
    var cargando by mutableStateOf(false)
        private set
    var mensaje by mutableStateOf<String?>(null)
        private set
    var estadoConexion by mutableStateOf("Desconectado")
        private set
    var desde by mutableStateOf(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).toString())
        private set
    var hasta by mutableStateOf(LocalDate.now(ZoneOffset.UTC).toString())
        private set
    var filtroTipo by mutableStateOf("")
        private set
    var busqueda by mutableStateOf("")
        private set

    private fun ejecutar(accion: suspend () -> Unit) {
        if (cargando) return
        cargando = true
        mensaje = null
        viewModelScope.launch {
            try { accion() } catch (e: CancellationException) { throw e } catch (e: ApiException) {
                if (e.codigo == 401) limpiarSesion()
                mensaje = e.message
            } catch (e: IOException) {
                mensaje = if (pendiente != null)
                    "No se confirmó el resultado. Usa Reintentar la misma operación; conserva esta pantalla."
                else "No se pudo conectar. Revisa el servidor y vuelve a actualizar."
            } catch (e: Exception) { mensaje = "No se pudo procesar la respuesta: ${e.message}" } finally {
                cargando = false
                if (refrescoPendiente && token != null) {
                    refrescoPendiente = false
                    actualizarSaldo()
                }
            }
        }
    }
    private suspend fun cargar() {
        val t = token ?: return
        val p = api.perfil(t)
        val lista = api.cuentas(t, p)
        perfil = p
        cuentas = lista
        cuenta = lista.firstOrNull { it.id == cuenta?.id } ?: lista.firstOrNull()
        val id = cuenta?.id ?: return
        val pagina = api.historial(t, id, desde, hasta, filtroTipo, busqueda)
        movimientos = pagina.items
        siguiente = pagina.siguiente
        resumen = api.resumen(t, id, desde, hasta)
    }
    fun ingresar(usuario: String, password: String) {
        if (usuario.isBlank() || password.isBlank()) { mensaje = "Completa usuario y contraseña."; return }
        ejecutar {
            val nuevo = api.login(usuario.trim().lowercase(), password)
            token = nuevo
            try {
                cargar()
                servicios = api.servicios(nuevo)
                iniciarTiempoReal(nuevo)
            } catch (e: Exception) { limpiarSesion(); throw e }
        }
    }
    fun registrar(nombre: String, usuario: String, password: String, cedula: String,
                  tipo: String, alias: String) {
        if (nombre.trim().length !in 2..80 || !Regex("^[a-z0-9_]{4,30}$").matches(usuario.trim().lowercase()) ||
            password.length !in 8..128 || !Regex("^[0-9]{10}$").matches(cedula) || alias.trim().length !in 2..40) {
            mensaje = "Revisa nombre (2–80), usuario (4–30), cédula (10 dígitos), contraseña (8–128) y alias (2–40)."; return
        }
        ejecutar {
            api.registrar(nombre.trim(), usuario.trim().lowercase(), password, cedula, tipo, alias.trim())
            mensaje = "Cuenta creada. Pulsa «Ya tengo cuenta» e inicia sesión."
        }
    }
    fun completarCedula(cedula: String) {
        if (!Regex("^[0-9]{10}$").matches(cedula)) { mensaje = "La cédula debe tener 10 dígitos."; return }
        ejecutar { api.completarCedula(token ?: return@ejecutar, cedula); cargar(); mensaje = "Cédula registrada." }
    }
    fun crearCuenta(tipo: String, alias: String) {
        if (alias.trim().length !in 2..40) { mensaje = "Alias: entre 2 y 40 caracteres."; return }
        ejecutar { api.crearCuenta(token ?: return@ejecutar, tipo, alias.trim()); cargar(); mensaje = "Cuenta creada." }
    }
    fun seleccionarCuenta(id: Long) {
        if (pendiente != null || cargando) return
        beneficiario = null
        cuenta = cuentas.firstOrNull { it.id == id } ?: return
        movimientos = emptyList(); resumen = null; siguiente = null
        actualizarSaldo()
    }
    fun actualizarSaldo() = ejecutar { cargar() }
    fun filtrar(inicio: String, fin: String, tipo: String, texto: String) {
        val valido = runCatching {
            val a = LocalDate.parse(inicio); val b = LocalDate.parse(fin)
            !a.isAfter(b) && java.time.temporal.ChronoUnit.DAYS.between(a, b) <= 366
        }.getOrDefault(false)
        if (!valido) { mensaje = "Fechas AAAA-MM-DD, en orden y con máximo 366 días."; return }
        desde = inicio; hasta = fin; filtroTipo = tipo; busqueda = texto.take(80)
        actualizarSaldo()
    }
    fun cargarMas() {
        val cursor = siguiente ?: return
        ejecutar {
            val pagina = api.historial(token ?: return@ejecutar, cuenta?.id ?: return@ejecutar,
                desde, hasta, filtroTipo, busqueda, cursor)
            movimientos = (movimientos + pagina.items).distinctBy { it.id }; siguiente = pagina.siguiente
        }
    }
    fun verComprobante(id: Long) = ejecutar {
        comprobante = api.comprobante(token ?: return@ejecutar, id)
    }
    fun cerrarComprobante() { comprobante = null }
    private fun centavos(texto: String): Long? = try {
        val limpio = texto.trim().replace(',', '.')
        if (!Regex("^[0-9]+(\\.[0-9]{1,2})?$").matches(limpio)) null
        else BigDecimal(limpio).movePointRight(2).longValueExact().takeIf { it in 1..100_000_000 }
    } catch (_: Exception) { null }
    fun validar(tipo: String, numero: String, monto: String) {
        if (pendiente != null) return
        val valor = centavos(monto)
        if (valor == null || !Regex("^[0-9]{10}$").matches(numero)) {
            mensaje = "Ingresa una cuenta de 10 dígitos y un monto positivo con hasta 2 decimales."; return
        }
        ejecutar {
            beneficiario = null
            beneficiario = api.validar(token ?: return@ejecutar, tipo, numero, valor,
                if (tipo == "TRANSFERENCIA") cuenta?.id else null)
            tipoValidado = tipo
        }
    }
    fun descartarValidacion() { if (pendiente == null && !cargando) beneficiario = null }
    fun confirmarDestinatario() {
        val b = beneficiario ?: return
        if (pendiente != null || cargando) return
        if (b.expira <= System.currentTimeMillis() / 1000) { beneficiario = null; mensaje = "Confirmación vencida. Valida otra vez."; return }
        val datos = JSONObject().put("solicitud_id", UUID.randomUUID().toString()).put("confirmacion_token", b.token)
        pendiente = OrdenPendiente("/operaciones/${if (tipoValidado == "DEPOSITO") "deposito" else "transferencia"}",
            datos.toString(), tipoValidado)
        enviarPendiente()
    }
    fun operar(tipo: String, monto: String, servicio: String = "", referencia: String = "") {
        if (pendiente != null || cargando) return
        val valor = centavos(monto)
        if (valor == null) { mensaje = "Monto positivo con hasta 2 decimales."; return }
        if (tipo == "PAGO_SERVICIO" && (servicios.none { it.codigo == servicio } || referencia.trim().length !in 3..60)) {
            mensaje = "Selecciona un servicio y una referencia de 3 a 60 caracteres."; return
        }
        val datos = JSONObject().put("solicitud_id", UUID.randomUUID().toString())
            .put("cuenta_origen_id", cuenta?.id ?: return).put("monto_centavos", valor)
        if (tipo == "PAGO_SERVICIO") datos.put("servicio", servicio).put("referencia", referencia.trim())
        pendiente = OrdenPendiente("/operaciones/${if (tipo == "RETIRO") "retiro" else "pago-servicio"}", datos.toString(), tipo)
        enviarPendiente()
    }
    fun enviarPendiente() {
        val orden = pendiente ?: return
        ejecutar {
            val r = try { api.operar(token ?: return@ejecutar, orden.ruta, orden.cuerpo) } catch (e: ApiException) {
                // Respuestas 4xx son rechazo explícito; 5xx/errores de red conservan UUID y cuerpo.
                if (e.codigo in 400..499) { pendiente = null; beneficiario = null }
                throw e
            }
            pendiente = null; beneficiario = null
            resultado = "${orden.titulo.replace('_', ' ')} confirmada\nMonto: ${dinero(r.getLong("monto_centavos"))}\nOperación: ${r.getString("operacion_id")}"
            cargar()
        }
    }
    fun cerrarResultado() { resultado = null }
    fun cerrarSesion() {
        if (pendiente != null) { mensaje = "Resuelve primero la operación pendiente."; return }
        ejecutar { api.logout(token ?: return@ejecutar); limpiarSesion(); mensaje = "Sesión cerrada." }
    }
    private fun limpiarSesion() {
        token = null; reconexion?.cancel(); socket?.cancel(); socket = null
        cuenta = null; cuentas = emptyList(); perfil = null; servicios = emptyList()
        movimientos = emptyList(); resumen = null; siguiente = null
        beneficiario = null; pendiente = null; comprobante = null; resultado = null
        filtroTipo = ""; busqueda = ""; estadoConexion = "Desconectado"
        refrescoPendiente = false
    }
    private fun iniciarTiempoReal(t: String) {
        reconexion?.cancel(); socket?.cancel(); socket = null
        reconexion = viewModelScope.launch {
            while (token == t) {
                if (socket == null) {
                    estadoConexion = "Conectando…"
                    socket = api.conectar(t, object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) {
                            viewModelScope.launch {
                                if (token == t && socket === ws) {
                                    estadoConexion = "En vivo"
                                    if (cargando) refrescoPendiente = true else actualizarSaldo()
                                }
                            }
                        }
                        override fun onMessage(ws: WebSocket, text: String) {
                            val evento = runCatching { JSONObject(text).optString("evento") }.getOrNull()
                            if (evento == "cuenta_actualizada") viewModelScope.launch {
                                if (token == t && socket === ws) {
                                    if (cargando) refrescoPendiente = true else actualizarSaldo()
                                }
                            }
                        }
                        private fun desconectado(ws: WebSocket) { viewModelScope.launch {
                            if (token == t && socket === ws) { socket = null; estadoConexion = "Reconectando…" }
                        } }
                        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = desconectado(ws)
                        override fun onClosed(ws: WebSocket, code: Int, reason: String) = desconectado(ws)
                        override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
                    })
                }
                delay(5000)
            }
        }
    }
    fun limpiarMensaje() { mensaje = null }
    override fun onCleared() { reconexion?.cancel(); socket?.cancel(); super.onCleared() }
}
