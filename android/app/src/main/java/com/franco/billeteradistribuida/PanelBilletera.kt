package com.franco.billeteradistribuida

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

private val AzulFs = Color(0xFF142D4E)
private val VerdeFs = Color(0xFFDCEBB5)
private val VerdeIngreso = Color(0xFF16704A)
private val GrisFs = Color(0xFF667085)
private val FondoSuave = Color(0xFFF3F6F8)

// Selector reutilizado en registro, cuentas, servicios y filtros.
@Composable
fun SelectorFs(etiqueta: String, valor: String, opciones: List<Pair<String, String>>,
               habilitado: Boolean = true, seleccionar: (String) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Column {
        Text(etiqueta, fontSize = 12.sp, color = GrisFs)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { abierto = true }, enabled = habilitado && opciones.isNotEmpty(),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(opciones.firstOrNull { it.first == valor }?.second ?: "Selecciona una opción",
                    modifier = Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = abierto && habilitado, onDismissRequest = { abierto = false }) {
                opciones.forEach { (codigo, nombre) ->
                    DropdownMenuItem(text = { Text(nombre) }, onClick = { abierto = false; seleccionar(codigo) })
                }
            }
        }
    }
}

@Composable
private fun BloqueFs(color: Color = FondoSuave, contenido: @Composable ColumnScope.() -> Unit) {
    Surface(color = color, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = contenido)
    }
}

@Composable
private fun CampoFs(etiqueta: String, valor: String, habilitado: Boolean = true,
                    numerico: Boolean = false, cambio: (String) -> Unit) {
    OutlinedTextField(value = valor, onValueChange = cambio, label = { Text(etiqueta) },
        enabled = habilitado, singleLine = true, shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (numerico) KeyboardType.Decimal else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth())
}

@Composable
fun PanelBilletera(vm: BilleteraViewModel) {
    val cuenta = vm.cuenta ?: return
    var seccion by rememberSaveable { mutableStateOf("INICIO") }
    val libre = !vm.cargando && vm.pendiente == null && vm.beneficiario == null
    Scaffold(containerColor = Color.White) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Image(painterResource(R.drawable.logo_fs), "FS", Modifier.width(82.dp).height(36.dp))
                Text(vm.estadoConexion, color = if (vm.estadoConexion == "En vivo") VerdeIngreso else GrisFs,
                    fontSize = 12.sp)
            }
            Text("Hola, ${cuenta.nombre}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Tu dinero, a tu alcance.", color = GrisFs)
            CarruselCuentas(vm = vm, habilitado = libre)
            if (vm.cargando) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.mensaje?.let { BloqueFs(Color(0xFFFFF2D9)) { Text(it, color = AzulFs) } }
            vm.pendiente?.let {
                BloqueFs(Color(0xFFFFF2D9)) {
                    Text("Operación pendiente de respuesta", fontWeight = FontWeight.Bold)
                    Text("No cierres la aplicación. Si hubo un corte, reintenta: se enviará el mismo identificador.")
                    Button(onClick = vm::enviarPendiente, enabled = !vm.cargando) { Text("Reintentar la misma operación") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("INICIO" to "Inicio", "OPERAR" to "Operaciones", "HISTORIAL" to "Historial", "CUENTAS" to "Mis cuentas").forEach { (id, titulo) ->
                    FilterChip(selected = seccion == id, onClick = { seccion = id; vm.limpiarMensaje() },
                        enabled = libre, label = { Text(titulo) })
                }
            }
            when (seccion) {
                "INICIO" -> ResumenFs(vm)
                "OPERAR" -> FormularioOperacion(vm)
                "HISTORIAL" -> HistorialFs(vm)
                "CUENTAS" -> CuentasFs(vm)
            }
            OutlinedButton(onClick = vm::actualizarSaldo, enabled = !vm.cargando,
                modifier = Modifier.fillMaxWidth()) { Text("Actualizar datos") }
            TextButton(onClick = vm::cerrarSesion, enabled = libre, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
            Text("Entorno académico · Operaciones y pagos simulados", color = GrisFs, fontSize = 12.sp)
        }
    }
    vm.beneficiario?.let { b ->
        AlertDialog(onDismissRequest = { vm.descartarValidacion() },
            title = { Text("Confirma el destinatario") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Titular", color = GrisFs); Text(b.titular, fontWeight = FontWeight.Bold)
                Text("Cuenta: ${b.numero}")
                Text("Monto: ${dinero(b.monto)}", fontWeight = FontWeight.Bold)
                Text("Comprueba que estos datos correspondan a quien recibirá el dinero.")
                vm.mensaje?.let { Text(it) }
            } },
            confirmButton = { Button(onClick = {
                if (vm.pendiente != null) vm.enviarPendiente() else vm.confirmarDestinatario()
            }, enabled = !vm.cargando) {
                Text(if (vm.pendiente != null) "Reintentar misma operación" else "Confirmar operación")
            } },
            dismissButton = { TextButton(onClick = vm::descartarValidacion,
                enabled = !vm.cargando && vm.pendiente == null) { Text("Cancelar") } })
    }
    vm.resultado?.let { texto ->
        AlertDialog(onDismissRequest = vm::cerrarResultado, title = { Text("Operación realizada") },
            text = { Text(texto) }, confirmButton = { TextButton(onClick = vm::cerrarResultado) { Text("Aceptar") } })
    }
    vm.comprobante?.let { m ->
        AlertDialog(onDismissRequest = vm::cerrarComprobante, title = { Text("Comprobante") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(m.tipo.replace('_', ' '), fontWeight = FontWeight.Bold)
                Text(dinero(m.monto), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(m.detalle); Text("Cuenta: ${m.numero}")
                Text("Fecha: ${fechaLocal(m.fecha)}")
                Text("Saldo resultante: ${dinero(m.saldo)}")
                Text("Operación: ${m.operacion}", fontSize = 12.sp)
                Text("Comprobante de una operación simulada.", color = GrisFs)
            } }, confirmButton = { TextButton(onClick = vm::cerrarComprobante) { Text("Cerrar") } })
    }
}

@Composable
private fun ResumenFs(vm: BilleteraViewModel) {
    Text("Resumen de tu cuenta", fontSize = 21.sp, fontWeight = FontWeight.Bold)
    Text("${vm.desde} al ${vm.hasta} · Fechas del servidor (UTC)", fontSize = 12.sp, color = GrisFs)
    val r = vm.resumen
    if (r == null) { Text("Actualiza para consultar el resumen."); return }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Surface(color = Color(0xFFE8F5EE), shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(16.dp)) { Text("Ingresos", color = VerdeIngreso); Text(dinero(r.ingresos), fontWeight = FontWeight.Bold, fontSize = 19.sp) }
        }
        Surface(color = Color(0xFFEAF0F8), shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(16.dp)) { Text("Egresos", color = AzulFs); Text(dinero(r.egresos), fontWeight = FontWeight.Bold, fontSize = 19.sp) }
        }
    }
    BloqueFs {
        Text("Actividad diaria", fontWeight = FontWeight.Bold)
        Text("${r.cantidad} movimientos · Neto ${dinero(r.ingresos - r.egresos)}", fontSize = 13.sp)
        Text("Verde: ingresos · Azul: egresos", color = GrisFs, fontSize = 12.sp)
        val maximo = r.dias.maxOfOrNull { maxOf(it.ingresos, it.egresos) }?.coerceAtLeast(1) ?: 1L
        if (r.dias.isEmpty()) Text("Aún no hay movimientos en este período.")
        r.dias.forEach { dia ->
            Text(dia.fecha, fontSize = 12.sp)
            BarraFs(dia.ingresos, maximo, VerdeIngreso)
            BarraFs(dia.egresos, maximo, AzulFs)
        }
        Text("Los traspasos entre tus cuentas también aparecen como entradas y salidas.", fontSize = 12.sp, color = GrisFs)
    }
    Text("Últimos movimientos del filtro actual", fontWeight = FontWeight.Bold)
    vm.movimientos.take(3).forEach { MovimientoFs(it, !vm.cargando) { vm.verComprobante(it.id) } }
}

@Composable
private fun BarraFs(valor: Long, maximo: Long, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(9.dp).background(Color.White, RoundedCornerShape(6.dp))) {
            if (valor > 0) Box(Modifier.fillMaxWidth((valor.toDouble() / maximo).toFloat().coerceIn(0f, 1f))
                .height(9.dp).background(color, RoundedCornerShape(6.dp)))
        }
        Text(dinero(valor), fontSize = 11.sp, color = color)
    }
}

@Composable
private fun CuentasFs(vm: BilleteraViewModel) {
    var tipo by rememberSaveable { mutableStateOf("NORMAL") }
    var alias by rememberSaveable { mutableStateOf("") }
    var cedula by rememberSaveable { mutableStateOf("") }
    val enabled = !vm.cargando && vm.pendiente == null
    Text("Tus cuentas", fontSize = 21.sp, fontWeight = FontWeight.Bold)
    vm.cuentas.forEach { c -> BloqueFs {
        Text(c.alias, fontWeight = FontWeight.Bold)
        Text("${c.numero} · ${c.tipo}")
        Text(dinero(c.saldoCentavos), color = AzulFs)
    } }
    if (vm.perfil?.cedula == null) {
        BloqueFs {
            Text("Completa tu identificación", fontWeight = FontWeight.Bold)
            Text("Tu usuario es anterior al registro con cédula.")
            CampoFs("Cédula (10 dígitos)", cedula, enabled, true) { cedula = it.filter { c -> c in '0'..'9' }.take(10) }
            Button(onClick = { vm.completarCedula(cedula) }, enabled = enabled) { Text("Guardar cédula") }
        }
    }
    BloqueFs {
        Text("Crear otra cuenta", fontWeight = FontWeight.Bold)
        SelectorFs("Tipo de cuenta", tipo, listOf("NORMAL" to "Normal", "AHORROS" to "Ahorros"), enabled) { tipo = it }
        CampoFs("Alias: Personal, Trabajo…", alias, enabled) { alias = it.take(40) }
        Text("El servidor asignará un número único de 10 dígitos.", color = GrisFs, fontSize = 12.sp)
        Button(onClick = { vm.crearCuenta(tipo, alias) }, enabled = enabled && vm.perfil?.cedula != null,
            modifier = Modifier.fillMaxWidth()) { Text("Crear cuenta") }
    }
}

@Composable
private fun FormularioOperacion(vm: BilleteraViewModel) {
    var tipo by rememberSaveable { mutableStateOf("TRANSFERENCIA") }
    var destino by rememberSaveable { mutableStateOf("") }
    var monto by rememberSaveable { mutableStateOf("") }
    var servicio by rememberSaveable { mutableStateOf("") }
    var referencia by rememberSaveable { mutableStateOf("") }
    var confirmar by remember { mutableStateOf(false) }
    val enabled = !vm.cargando && vm.pendiente == null && vm.beneficiario == null
    Text("Realiza una operación", fontSize = 21.sp, fontWeight = FontWeight.Bold)
    SelectorFs("Operación", tipo, listOf("TRANSFERENCIA" to "Transferir", "DEPOSITO" to "Depositar",
        "RETIRO" to "Retirar", "PAGO_SERVICIO" to "Pagar un servicio"), enabled) { tipo = it }
    if (tipo != "DEPOSITO") Text("Desde: ${vm.cuenta?.alias} · ${vm.cuenta?.numero}", color = AzulFs)
    if (tipo == "DEPOSITO" || tipo == "TRANSFERENCIA") {
        CampoFs("Número de cuenta destinataria", destino, enabled, true) { destino = it.filter { c -> c in '0'..'9' }.take(10) }
    }
    if (tipo == "PAGO_SERVICIO") {
        SelectorFs("Servicio a pagar", servicio, vm.servicios.map { it.codigo to it.nombre }, enabled) { servicio = it }
        CampoFs(vm.servicios.firstOrNull { it.codigo == servicio }?.referencia ?: "Referencia del servicio",
            referencia, enabled) { referencia = it.take(60) }
    }
    CampoFs("Monto en USD (ejemplo: 10.50)", monto, enabled, true) { monto = it.take(14) }
    Button(onClick = {
        if (tipo == "TRANSFERENCIA" || tipo == "DEPOSITO") vm.validar(tipo, destino, monto)
        else confirmar = true
    }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
        Text(if (tipo == "TRANSFERENCIA" || tipo == "DEPOSITO") "Consultar destinatario" else "Revisar operación")
    }
    Text("Depósitos, retiros y servicios son simulados para el proyecto.", fontSize = 12.sp, color = GrisFs)
    if (confirmar) AlertDialog(onDismissRequest = { confirmar = false }, title = { Text("Revisa los datos") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Cuenta: ${vm.cuenta?.numero}"); Text("Monto USD: $monto")
            if (tipo == "PAGO_SERVICIO") { Text(vm.servicios.firstOrNull { it.codigo == servicio }?.nombre.orEmpty()); Text("Referencia: $referencia") }
            else Text("Retiro simulado")
        } }, confirmButton = { Button(onClick = { confirmar = false; vm.operar(tipo, monto, servicio, referencia) }) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = { confirmar = false }) { Text("Cancelar") } })
}

@Composable
private fun HistorialFs(vm: BilleteraViewModel) {
    var desde by rememberSaveable { mutableStateOf(vm.desde) }
    var hasta by rememberSaveable { mutableStateOf(vm.hasta) }
    var tipo by rememberSaveable { mutableStateOf(vm.filtroTipo) }
    var buscar by rememberSaveable { mutableStateOf(vm.busqueda) }
    Text("Historial", fontSize = 21.sp, fontWeight = FontWeight.Bold)
    BloqueFs {
        CampoFs("Desde (AAAA-MM-DD)", desde, !vm.cargando) { desde = it.take(10) }
        CampoFs("Hasta (AAAA-MM-DD)", hasta, !vm.cargando) { hasta = it.take(10) }
        SelectorFs("Tipo de movimiento", tipo, listOf("" to "Todos", "DEPOSITO" to "Depósitos",
            "RETIRO" to "Retiros", "TRANSFERENCIA_ENVIADA" to "Transferencias enviadas",
            "TRANSFERENCIA_RECIBIDA" to "Transferencias recibidas", "PAGO_SERVICIO" to "Servicios"), !vm.cargando) { tipo = it }
        CampoFs("Buscar en el detalle", buscar, !vm.cargando) { buscar = it.take(80) }
        Button(onClick = { vm.filtrar(desde, hasta, tipo, buscar) }, enabled = !vm.cargando) { Text("Aplicar filtros") }
        Text("Rango por fecha UTC. El resumen usa estas fechas; sus totales incluyen todos los tipos.", fontSize = 12.sp, color = GrisFs)
    }
    if (vm.movimientos.isEmpty()) Text("No hay movimientos para estos filtros.", color = GrisFs)
    vm.movimientos.forEach { m -> MovimientoFs(m, !vm.cargando) { vm.verComprobante(m.id) } }
    if (vm.siguiente != null) OutlinedButton(onClick = vm::cargarMas, enabled = !vm.cargando,
        modifier = Modifier.fillMaxWidth()) { Text("Cargar más") }
}

private fun fechaLocal(texto: String): String = runCatching {
    LocalDateTime.parse(texto.replace(' ', 'T')).atOffset(ZoneOffset.UTC)
        .atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
}.getOrDefault(texto)

@Composable
private fun MovimientoFs(m: Movimiento, enabled: Boolean, abrir: () -> Unit) {
    BloqueFs {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (m.ingreso) "Ingreso" else "Egreso", color = GrisFs, fontSize = 12.sp)
            Text((if (m.ingreso) "+" else "−") + dinero(m.monto),
                fontWeight = FontWeight.Bold, color = if (m.ingreso) VerdeIngreso else AzulFs)
        }
        Text(m.detalle, fontWeight = FontWeight.SemiBold)
        Text(fechaLocal(m.fecha), fontSize = 12.sp, color = GrisFs)
        TextButton(onClick = abrir, enabled = enabled) { Text("Ver comprobante") }
    }
}

@Composable
private fun CarruselCuentas(
    vm: BilleteraViewModel,
    habilitado: Boolean
) {
    val cuentas = vm.cuentas
    if (cuentas.isEmpty()) return

    // Conserva la selección al agregar cuentas o reconstruir la pantalla.
    key(cuentas.map { it.id }) {
        val paginaInicial = cuentas.indexOfFirst {
            it.id == vm.cuenta?.id
        }.coerceAtLeast(0)

        val pager = rememberPagerState(
            initialPage = paginaInicial,
            pageCount = { cuentas.size }
        )

        // Cambia la cuenta cuando la tarjeta termina de deslizarse.
        LaunchedEffect(
            pager.settledPage,
            pager.isScrollInProgress,
            habilitado
        ) {
            if (habilitado && !pager.isScrollInProgress) {
                val seleccionada = cuentas.getOrNull(pager.settledPage)

                if (
                    seleccionada != null &&
                    seleccionada.id != vm.cuenta?.id
                ) {
                    vm.seleccionarCuenta(seleccionada.id)
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Mis cuentas",
                    color = AzulFs,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "${pager.settledPage + 1} de ${cuentas.size}",
                    color = GrisFs,
                    fontSize = 12.sp
                )
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth(),
                key = { pagina -> cuentas[pagina].id },
                pageSpacing = 12.dp,
                contentPadding = PaddingValues(
                    end = if (cuentas.size > 1) 36.dp else 0.dp
                ),
                userScrollEnabled = habilitado
            ) { pagina ->
                val tarjeta = cuentas[pagina]

                Surface(
                    color = VerdeFs,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "SALDO DISPONIBLE",
                            color = AzulFs,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = dinero(tarjeta.saldoCentavos),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )

                        HorizontalDivider(
                            color = AzulFs.copy(alpha = 0.12f)
                        )

                        Text(
                            text = tarjeta.alias,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )

                        Text(
                            text = if (tarjeta.tipo == "AHORROS")
                                "Cuenta de ahorros"
                            else
                                "Cuenta normal",
                            color = AzulFs,
                            fontSize = 13.sp
                        )

                        Text(
                            text = tarjeta.numero,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "@${tarjeta.usuario}",
                            color = AzulFs,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            if (cuentas.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(cuentas.size) { indice ->
                        val seleccionada = indice == pager.settledPage

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .width(if (seleccionada) 22.dp else 7.dp)
                                .height(7.dp)
                                .background(
                                    color = if (seleccionada)
                                        AzulFs
                                    else
                                        AzulFs.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                        )
                    }
                }

                Text(
                    text = "Desliza para cambiar de cuenta",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = GrisFs,
                    fontSize = 12.sp
                )
            }
        }
    }
}