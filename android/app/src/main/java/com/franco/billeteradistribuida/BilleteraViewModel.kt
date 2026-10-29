package com.franco.billeteradistribuida

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.franco.billeteradistribuida.datos.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class OrdenPendiente(val ruta: String, val cuerpo: String, val titulo: String)

class BilleteraViewModel(application: Application) : AndroidViewModel(application) {

    private val api = BilleteraApi()
    private val db = BilleteraDatabase.obtenerInstancia(application)
    private val movimientoDao = db.movimientoDao()
    private val cuentaDao = db.cuentaDao()
    private val sesionDao = db.sesionDao()

    private var token: String? = null
    private var socket: WebSocket? = null
    private var reconexion: Job? = null
    private var observadorHistorial: Job? = null
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
    var esModoOffline by mutableStateOf(false)
        private set
    var ultimaSincronizacionTexto by mutableStateOf<String?>(null)
        private set
    var sesionGuardadaDisponible by mutableStateOf(false)
        private set
    var usuarioSesionGuardada by mutableStateOf<String?>(null)
        private set

    var desde by mutableStateOf(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).toString())
        private set
    var hasta by mutableStateOf(LocalDate.now(ZoneOffset.UTC).toString())
        private set
    var filtroTipo by mutableStateOf("")
        private set
    var busqueda by mutableStateOf("")
        private set

    init {
        verificarSesionGuardada()
    }

    private fun verificarSesionGuardada() {
        viewModelScope.launch(Dispatchers.IO) {
            val sesion = sesionDao.obtenerSesionActiva()
            withContext(Dispatchers.Main) {
                if (sesion != null) {
                    sesionGuardadaDisponible = true
                    usuarioSesionGuardada = sesion.usuario
                }
            }
        }
    }

    private fun formatearFechaHora(millis: Long): String = runCatching {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    }.getOrDefault("")

    private fun ejecutar(accion: suspend () -> Unit) {
        if (cargando) return
        cargando = true
        mensaje = null
        viewModelScope.launch {
            try {
                accion()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.codigo == 401) limpiarSesion()
                mensaje = e.message
            } catch (e: IOException) {
                mensaje = if (pendiente != null)
                    "No se confirmó el resultado. Usa Reintentar la misma operación; conserva esta pantalla."
                else
                    "No se pudo conectar con el servidor. Revisa tu conexión o el estado del backend."
            } catch (e: Exception) {
                mensaje = "No se pudo procesar la respuesta: ${e.message}"
            } finally {
                cargando = false
                if (refrescoPendiente && token != null) {
                    refrescoPendiente = false
                    actualizarSaldo()
                }
            }
        }
    }

    /**
     * Inicia la observación reactiva del historial desde Room mediante Flow.
     * Cualquier inserción o modificación en SQLite se refleja de inmediato en la UI.
     * Cancela observadores previos para evitar duplicados y fugas.
     */
    private fun iniciarObservadorHistorial(cuentaId: Long, usuario: String) {
        observadorHistorial?.cancel()
        observadorHistorial = viewModelScope.launch {
            movimientoDao.observarMovimientos(
                cuentaId = cuentaId,
                usuario = usuario,
                tipo = filtroTipo,
                buscar = busqueda,
                desde = desde,
                hasta = hasta
            ).flowOn(Dispatchers.IO)
             .collect { listaLocal ->
                 // Sincronización al hilo principal para actualizar la interfaz
                 movimientos = listaLocal.map { it.aModelo() }
             }
        }
    }

    private suspend fun cargarOnline(t: String) {
        val p = withContext(Dispatchers.IO) { api.perfil(t) }
        val lista = withContext(Dispatchers.IO) { api.cuentas(t, p) }
        val u = p.usuario.lowercase()

        perfil = p
        cuentas = lista
        cuenta = lista.firstOrNull { it.id == cuenta?.id } ?: lista.firstOrNull()

        // Guardar cuentas en Room
        withContext(Dispatchers.IO) {
            cuentaDao.guardarCuentas(lista.map { it.aEntidad(u) })
        }

        val id = cuenta?.id ?: return
        val pagina = withContext(Dispatchers.IO) {
            api.historial(t, id, desde, hasta, filtroTipo, busqueda)
        }

        // Guardar historial en Room sin borrar registros existentes
        withContext(Dispatchers.IO) {
            movimientoDao.guardarMovimientos(pagina.items.map { it.aEntidad(id, u) })
        }

        siguiente = pagina.siguiente
        resumen = withContext(Dispatchers.IO) { api.resumen(t, id, desde, hasta) }
        ultimaSincronizacionTexto = formatearFechaHora(System.currentTimeMillis())

        iniciarObservadorHistorial(id, u)
    }

    private suspend fun cargarOffline(u: String) {
        val listaCuentas = withContext(Dispatchers.IO) {
            cuentaDao.obtenerCuentas(u).map { it.aModelo() }
        }
        cuentas = listaCuentas
        cuenta = listaCuentas.firstOrNull { it.id == cuenta?.id } ?: listaCuentas.firstOrNull()
        val id = cuenta?.id ?: return
        iniciarObservadorHistorial(id, u)
    }

    fun ingresar(usuario: String, password: String) {
        val u = usuario.trim().lowercase()
        if (u.isBlank() || password.isBlank()) {
            mensaje = "Completa usuario y contraseña."
            return
        }
        ejecutar {
            try {
                val nuevo = withContext(Dispatchers.IO) {
                    api.login(u, password)
                }
                token = nuevo
                esModoOffline = false
                try {
                    cargarOnline(nuevo)
                    // Almacenamiento seguro de credenciales con salt/hash SHA-256 (nunca texto plano)
                    withContext(Dispatchers.IO) {
                        sesionDao.desactivarTodas()
                        val salt = SeguridadLocal.generarSalt()
                        val hash = SeguridadLocal.calcularHash(password, salt)
                        val sesionLocal = SesionEntity(
                            usuario = u,
                            nombre = perfil?.nombre ?: u,
                            cedula = perfil?.cedula,
                            passwordHash = hash,
                            salt = salt,
                            ultimaSincronizacion = System.currentTimeMillis(),
                            activa = true
                        )
                        sesionDao.guardarSesion(sesionLocal)
                    }
                    servicios = withContext(Dispatchers.IO) { api.servicios(nuevo) }
                    iniciarTiempoReal(nuevo)
                } catch (e: Exception) {
                    limpiarSesion()
                    throw e
                }
            } catch (e: IOException) {
                // Modo offline: verificar credenciales localmente sin enviar a red
                val sesionLocal = withContext(Dispatchers.IO) {
                    sesionDao.obtenerPorUsuario(u)
                }
                if (sesionLocal != null && SeguridadLocal.verificarPassword(password, sesionLocal.salt, sesionLocal.passwordHash)) {
                    token = null
                    esModoOffline = true
                    estadoConexion = "Sin conexión (Modo local)"
                    perfil = Perfil(sesionLocal.nombre, sesionLocal.usuario, sesionLocal.cedula)
                    ultimaSincronizacionTexto = formatearFechaHora(sesionLocal.ultimaSincronizacion)
                    cargarOffline(u)
                    mensaje = "Modo sin conexión. Mostrando historial guardado en Room."
                } else if (sesionLocal != null) {
                    mensaje = "Sin conexión con el servidor. La contraseña ingresada no coincide con los datos locales."
                } else {
                    mensaje = "No se pudo conectar al servidor y no existen datos locales para «$u»."
                }
            }
        }
    }

    fun registrar(nombre: String, usuario: String, password: String, cedula: String,
                  tipo: String, alias: String) {
        if (nombre.trim().length !in 2..80 || !Regex("^[a-z0-9_]{4,30}$").matches(usuario.trim().lowercase()) ||
            password.length !in 8..128 || !Regex("^[0-9]{10}$").matches(cedula) || alias.trim().length !in 2..40) {
            mensaje = "Revisa nombre (2–80), usuario (4–30), cédula (10 dígitos), contraseña (8–128) y alias (2–40)."
            return
        }
        ejecutar {
            withContext(Dispatchers.IO) {
                api.registrar(nombre.trim(), usuario.trim().lowercase(), password, cedula, tipo, alias.trim())
            }
            mensaje = "Cuenta creada. Pulsa «Ya tengo cuenta» e inicia sesión."
        }
    }

    fun completarCedula(cedula: String) {
        if (esModoOffline || token == null) {
            mensaje = "Esta operación requiere conexión activa con el servidor."
            return
        }
        if (!Regex("^[0-9]{10}$").matches(cedula)) {
            mensaje = "La cédula debe tener 10 dígitos."
            return
        }
        ejecutar {
            withContext(Dispatchers.IO) {
                api.completarCedula(token ?: return@withContext, cedula)
            }
            cargarOnline(token ?: return@ejecutar)
            mensaje = "Cédula registrada."
        }
    }

    fun crearCuenta(tipo: String, alias: String) {
        if (esModoOffline || token == null) {
            mensaje = "Crear cuenta requiere conexión activa con el servidor."
            return
        }
        if (alias.trim().length !in 2..40) {
            mensaje = "Alias: entre 2 y 40 caracteres."
            return
        }
        ejecutar {
            withContext(Dispatchers.IO) {
                api.crearCuenta(token ?: return@withContext, tipo, alias.trim())
            }
            cargarOnline(token ?: return@ejecutar)
            mensaje = "Cuenta creada."
        }
    }

    fun seleccionarCuenta(id: Long) {
        if (pendiente != null || cargando) return
        beneficiario = null
        cuenta = cuentas.firstOrNull { it.id == id } ?: return
        siguiente = null

        val u = perfil?.usuario ?: cuenta?.usuario.orEmpty()
        if (u.isNotEmpty()) {
            iniciarObservadorHistorial(id, u)
        }

        if (!esModoOffline && token != null) {
            actualizarSaldo()
        }
    }

    fun actualizarSaldo() = ejecutar {
        val t = token
        val u = perfil?.usuario ?: cuenta?.usuario.orEmpty()
        if (t != null && !esModoOffline) {
            cargarOnline(t)
        } else if (u.isNotEmpty()) {
            cargarOffline(u)
            mensaje = "Datos locales actualizados desde Room."
        }
    }

    fun filtrar(inicio: String, fin: String, tipo: String, texto: String) {
        val valido = runCatching {
            val a = LocalDate.parse(inicio)
            val b = LocalDate.parse(fin)
            !a.isAfter(b) && java.time.temporal.ChronoUnit.DAYS.between(a, b) <= 366
        }.getOrDefault(false)
        if (!valido) {
            mensaje = "Fechas AAAA-MM-DD, en orden y con máximo 366 días."
            return
        }
        desde = inicio
        hasta = fin
        filtroTipo = tipo
        busqueda = texto.take(80)

        val id = cuenta?.id
        val u = perfil?.usuario ?: cuenta?.usuario.orEmpty()
        if (id != null && u.isNotEmpty()) {
            iniciarObservadorHistorial(id, u)
        }

        if (!esModoOffline && token != null) {
            actualizarSaldo()
        }
    }

    fun cargarMas() {
        if (esModoOffline || token == null) {
            mensaje = "Historial completo disponible en almacenamiento local."
            return
        }
        val cursor = siguiente ?: return
        ejecutar {
            val t = token ?: return@ejecutar
            val c = cuenta ?: return@ejecutar
            val u = perfil?.usuario ?: c.usuario
            val pagina = withContext(Dispatchers.IO) {
                api.historial(t, c.id, desde, hasta, filtroTipo, busqueda, cursor)
            }
            withContext(Dispatchers.IO) {
                movimientoDao.guardarMovimientos(pagina.items.map { it.aEntidad(c.id, u) })
            }
            siguiente = pagina.siguiente
        }
    }

    fun verComprobante(id: Long) = ejecutar {
        val t = token
        val u = perfil?.usuario ?: cuenta?.usuario.orEmpty()
        if (t != null && !esModoOffline) {
            try {
                val comp = withContext(Dispatchers.IO) { api.comprobante(t, id) }
                val cId = cuenta?.id ?: 0L
                withContext(Dispatchers.IO) {
                    movimientoDao.guardarMovimiento(comp.aEntidad(cId, u))
                }
                comprobante = comp
                return@ejecutar
            } catch (_: Exception) {
                // Si la red falla, consulta la copia en Room
            }
        }
        val local = withContext(Dispatchers.IO) { movimientoDao.obtenerPorId(id, u) }
        if (local != null) {
            comprobante = local.aModelo()
        } else {
            mensaje = "Comprobante no disponible fuera de línea."
        }
    }

    fun cerrarComprobante() {
        comprobante = null
    }

    private fun centavos(texto: String): Long? = try {
        val limpio = texto.trim().replace(',', '.')
        if (!Regex("^[0-9]+(\\.[0-9]{1,2})?$").matches(limpio)) null
        else BigDecimal(limpio).movePointRight(2).longValueExact().takeIf { it in 1..100_000_000 }
    } catch (_: Exception) {
        null
    }

    fun validar(tipo: String, numero: String, monto: String) {
        if (esModoOffline || token == null) {
            mensaje = "Las operaciones monetarias requieren conexión al servidor. No disponibles sin conexión."
            return
        }
        if (pendiente != null) return
        val valor = centavos(monto)
        if (valor == null || !Regex("^[0-9]{10}$").matches(numero)) {
            mensaje = "Ingresa una cuenta de 10 dígitos y un monto positivo con hasta 2 decimales."
            return
        }
        ejecutar {
            beneficiario = null
            val b = withContext(Dispatchers.IO) {
                api.validar(
                    token ?: return@withContext null,
                    tipo,
                    numero,
                    valor,
                    if (tipo == "TRANSFERENCIA") cuenta?.id else null
                )
            }
            beneficiario = b
            tipoValidado = tipo
        }
    }

    fun descartarValidacion() {
        if (pendiente == null && !cargando) beneficiario = null
    }

    fun confirmarDestinatario() {
        if (esModoOffline || token == null) {
            mensaje = "Las operaciones monetarias requieren conexión al servidor. No disponibles sin conexión."
            return
        }
        val b = beneficiario ?: return
        if (pendiente != null || cargando) return
        if (b.expira <= System.currentTimeMillis() / 1000) {
            beneficiario = null
            mensaje = "Confirmación vencida. Valida otra vez."
            return
        }
        val datos = JSONObject().put("solicitud_id", UUID.randomUUID().toString()).put("confirmacion_token", b.token)
        pendiente = OrdenPendiente(
            "/operaciones/${if (tipoValidado == "DEPOSITO") "deposito" else "transferencia"}",
            datos.toString(),
            tipoValidado
        )
        enviarPendiente()
    }

    fun operar(tipo: String, monto: String, servicio: String = "", referencia: String = "") {
        if (esModoOffline || token == null) {
            mensaje = "Las operaciones monetarias requieren conexión al servidor. No disponibles sin conexión."
            return
        }
        if (pendiente != null || cargando) return
        val valor = centavos(monto)
        if (valor == null) {
            mensaje = "Monto positivo con hasta 2 decimales."
            return
        }
        if (tipo == "PAGO_SERVICIO" && (servicios.none { it.codigo == servicio } || referencia.trim().length !in 3..60)) {
            mensaje = "Selecciona un servicio y una referencia de 3 a 60 caracteres."
            return
        }
        val datos = JSONObject().put("solicitud_id", UUID.randomUUID().toString())
            .put("cuenta_origen_id", cuenta?.id ?: return).put("monto_centavos", valor)
        if (tipo == "PAGO_SERVICIO") datos.put("servicio", servicio).put("referencia", referencia.trim())
        pendiente = OrdenPendiente(
            "/operaciones/${if (tipo == "RETIRO") "retiro" else "pago-servicio"}",
            datos.toString(),
            tipo
        )
        enviarPendiente()
    }

    fun enviarPendiente() {
        if (esModoOffline || token == null) {
            mensaje = "Las operaciones monetarias requieren conexión al servidor."
            return
        }
        val orden = pendiente ?: return
        ejecutar {
            val r = try {
                withContext(Dispatchers.IO) {
                    api.operar(token ?: return@withContext JSONObject(), orden.ruta, orden.cuerpo)
                }
            } catch (e: ApiException) {
                if (e.codigo in 400..499) {
                    pendiente = null
                    beneficiario = null
                }
                throw e
            }
            pendiente = null
            beneficiario = null
            resultado = "${orden.titulo.replace('_', ' ')} confirmada\nMonto: ${dinero(r.getLong("monto_centavos"))}\nOperación: ${r.getString("operacion_id")}"
            cargarOnline(token ?: return@ejecutar)
        }
    }

    fun cerrarResultado() {
        resultado = null
    }

    fun cerrarSesion() {
        if (pendiente != null) {
            mensaje = "Resuelve primero la operación pendiente."
            return
        }
        ejecutar {
            val t = token
            if (t != null && !esModoOffline) {
                try {
                    withContext(Dispatchers.IO) { api.logout(t) }
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.IO) {
                sesionDao.desactivarTodas()
            }
            limpiarSesion()
            mensaje = "Sesión cerrada."
        }
    }

    private fun limpiarSesion() {
        token = null
        reconexion?.cancel()
        socket?.cancel()
        socket = null
        observadorHistorial?.cancel()
        observadorHistorial = null
        cuenta = null
        cuentas = emptyList()
        perfil = null
        servicios = emptyList()
        movimientos = emptyList()
        resumen = null
        siguiente = null
        beneficiario = null
        pendiente = null
        comprobante = null
        resultado = null
        filtroTipo = ""
        busqueda = ""
        estadoConexion = "Desconectado"
        esModoOffline = false
        ultimaSincronizacionTexto = null
        refrescoPendiente = false
    }

    private fun iniciarTiempoReal(t: String) {
        reconexion?.cancel()
        socket?.cancel()
        socket = null
        reconexion = viewModelScope.launch {
            while (token == t && !esModoOffline) {
                if (socket == null) {
                    estadoConexion = "Conectando…"
                    socket = withContext(Dispatchers.IO) {
                        api.conectar(t, object : WebSocketListener() {
                            override fun onOpen(ws: WebSocket, response: Response) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    if (token == t && socket === ws) {
                                        estadoConexion = "En vivo"
                                        if (cargando) refrescoPendiente = true else actualizarSaldo()
                                    }
                                }
                            }

                            override fun onMessage(ws: WebSocket, text: String) {
                                val evento = runCatching { JSONObject(text).optString("evento") }.getOrNull()
                                if (evento == "cuenta_actualizada") {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        if (token == t && socket === ws) {
                                            if (cargando) refrescoPendiente = true else actualizarSaldo()
                                        }
                                    }
                                }
                            }

                            private fun desconectado(ws: WebSocket) {
                                viewModelScope.launch(Dispatchers.Main) {
                                    if (token == t && socket === ws) {
                                        socket = null
                                        estadoConexion = "Reconectando…"
                                    }
                                }
                            }

                            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = desconectado(ws)
                            override fun onClosed(ws: WebSocket, code: Int, reason: String) = desconectado(ws)
                            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                                ws.close(code, reason)
                            }
                        })
                    }
                }
                delay(5000)
            }
        }
    }

    fun limpiarMensaje() {
        mensaje = null
    }

    override fun onCleared() {
        reconexion?.cancel()
        socket?.cancel()
        observadorHistorial?.cancel()
        super.onCleared()
    }
}
