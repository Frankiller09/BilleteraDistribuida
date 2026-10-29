# Guía de Evidencias y Pruebas de Verificación
## Proyecto Final UC 1: Billetera Móvil Distribuida con Concurrencia y Persistencia

> **Aviso de Responsabilidad y Estado de Ejecución:**  
> En cumplimiento estricto de los límites operativos establecidos, **ningún comando de terminal, compilación, sincronización Gradle, levantamiento de servicios ni prueba automatizada o manual ha sido ejecutado por el asistente**. Todas las validaciones, capturas de pantalla y verificaciones funcionales quedan expresamente declaradas como **«Pendientes de ejecución y comprobación por el usuario»**.

---

## 1. Matriz de Cumplimiento Técnico (Rúbrica del Profesor)

| Criterio Evaluado | Puntos Rúbrica | Requisito Obligatorio del Profesor | Implementación en Código | Estado de Verificación |
| :--- | :---: | :--- | :--- | :---: |
| **Persistencia Local (Room)** | 4 pts | Crear BD, entidades y DAOs correctamente. Historial se guarda y sobrevive al reiniciar la app sin internet. Sin duplicados y sin borrado por respuestas parciales. | Entidades `MovimientoEntity`, `CuentaEntity`, `SesionEntity`, DAOs en `Daos.kt`, base `BilleteraDatabase.kt`, Room 2.7.2 con KSP. | *Pendiente de ejecución por el usuario* |
| **Integración REST (Emisión)** | 4 pts | Depósitos, Retiros, Transferencias y Pago de Servicios mediante REST comunicándose con backend y manejo de errores (4xx/5xx). | Endpoints `/operaciones/...`, manejo de `ApiException`, validación de fondos y confirmación de destinatario con token en `BilleteraApi.kt`. | *Pendiente de ejecución por el usuario* |
| **Tiempo Real (WebSockets)** | 4 pts | Mantener túnel abierto, escuchar transferencias entrantes y actualizar saldo automáticamente sin recargar. | `BilleteraViewModel.kt` conecta `WebSocketListener` a `/ws`, escucha evento `cuenta_actualizada` y actualiza Room y saldo. | *Pendiente de ejecución por el usuario* |
| **Concurrencia e Hilos** | 4 pts | Uso de Corrutinas: Red y BD en `Dispatchers.IO`, UI en `Dispatchers.Main`. Cero congelamientos (ANR). | Invocaciones REST y transacciones Room encapsuladas con `withContext(Dispatchers.IO)`, Flow con `flowOn(Dispatchers.IO)`, consumo en `viewModelScope`. | *Pendiente de ejecución por el usuario* |
| **Interfaz (UI) y UX** | 4 pts | Tarjeta de saldo destacado, indicador visual de conexión, panel de operaciones, historial con `LazyColumn`, Servicio Básico con icono distintivo. | Preservación de UI FS (carrusel, tarjetas, selectores), `LazyColumn` reactivo en historial, etiqueta "⚡ Servicio Básico" e indicador de estado de conexión. | *Pendiente de ejecución por el usuario* |

---

## 2. Diferencias Detectadas entre la Guía del Profesor y la Interfaz Actual

Conforme a tu aclaración de **no modificar automáticamente la interfaz**, a continuación se documentan las diferencias encontradas con sus fragmentos literales de la guía y el análisis técnico:

### A. Codificación de color en egresos (Azul Marino vs Rojo)
* **Texto del Profesor (Página 2):**  
  > *«Codificación visual: Montos en verde para ingresos (Depósitos, Transferencias recibidas) y en rojo para egresos (Retiros, Transferencias enviadas, Pagos de servicios).»*
* **Implementación Actual en tu UI:**  
  Tu diseño corporativo FS utiliza `VerdeIngreso = Color(0xFF16704A)` para ingresos y `AzulFs = Color(0xFF142D4E)` para egresos.
* **Ajuste mínimo opcional (si decides aplicarlo para el profesor):**  
  En `PanelBilletera.kt`, en la función `MovimientoFs`, cambiar el color condicional:
  ```kotlin
  // Actual:
  color = if (m.ingreso) VerdeIngreso else AzulFs
  // Si deseas rojo estricto según la guía:
  color = if (m.ingreso) VerdeIngreso else Color(0xFFBA1A1A)
  ```
  *Decisión:* Se conserva tu interfaz actual (`AzulFs`). Tú decides si cambias esa constante a rojo antes de la defensa.

### B. Grid de 4 botones vs Selector por Chips/Pestañas
* **Texto del Profesor (Página 2):**  
  > *«Panel de Acciones (Grid de Botones): Cuatro botones grandes, preferiblemente con iconos: [Depositar], [Retirar], [Transferir], [Pagar Servicios]. Al tocar uno, se debe abrir un cuadro de diálogo (Modal/Dialog)...»*
* **Implementación Actual en tu UI:**  
  Tu aplicación utiliza pestañas/chips superiores (`Inicio`, `Operaciones`, `Historial`, `Mis cuentas`) y dentro de `Operaciones` se selecciona la acción mediante el menú desplegable `SelectorFs`, ejecutando modales para confirmación de destinatario y revisión.
* **Ajuste:**  
  Se mantiene tu estructura y diseño intactos por tu instrucción explícita. Ambas modalidades cumplen con abrir el cuadro de diálogo para ingresar monto y destino.

---

## 3. Catálogo de Evidencias para Captura y Sustentación

A continuación se detallan las 10 evidencias exactas que debes capturar en código y en pantalla del dispositivo/emulador.

---

### EVIDENCIA 1: Entidades, DAOs y Base de Datos Room

* **Requisito del profesor:**  
  Persistencia local con Room (BD, entidades y DAOs correctamente implementados).
* **Archivos y rutas exactas:**  
  1. `android/app/src/main/java/com/franco/billeteradistribuida/datos/Entidades.kt`
  2. `android/app/src/main/java/com/franco/billeteradistribuida/datos/Daos.kt`
  3. `android/app/src/main/java/com/franco/billeteradistribuida/datos/BilleteraDatabase.kt`
* **Fragmento concreto a capturar:**
  ```kotlin
  @Entity(
      tableName = "movimientos",
      indices = [
          Index(value = ["cuentaId", "usuario"]),
          Index(value = ["usuario"]),
          Index(value = ["fecha"])
      ]
  )
  data class MovimientoEntity(
      @PrimaryKey val id: Long,
      val cuentaId: Long,
      val usuario: String,
      val tipo: String,
      val monto: Long,
      val saldo: Long,
      val detalle: String,
      val fecha: String,
      val operacion: String,
      val ingreso: Boolean,
      val numero: String,
      val categoriaVisual: String,
      val guardadoTimestamp: Long
  )
  ```
  ```kotlin
  @Database(
      entities = [MovimientoEntity::class, CuentaEntity::class, SesionEntity::class],
      version = 1,
      exportSchema = false
  )
  abstract class BilleteraDatabase : RoomDatabase() { ... }
  ```
* **Líneas aproximadas:**  
  - `Entidades.kt`: Líneas 10–35  
  - `BilleteraDatabase.kt`: Líneas 8–35
* **Pasos que TÚ debes ejecutar:**  
  Abrir Android Studio, navegar a `com.franco.billeteradistribuida.datos`, abrir `Entidades.kt` y `BilleteraDatabase.kt` y tomar captura del editor con la estructura de clases.
* **Resultado esperado:**  
  *Pendiente de ejecución por el usuario.*
* **Qué demuestra la captura:**  
  Demuestra que la aplicación utiliza la abstracción oficial de Google Room con anotaciones `@Entity`, `@PrimaryKey`, índices para búsqueda y `@Database` con SQLite.

---

### EVIDENCIA 2: Guardado Sin Duplicados y No Borrado por Respuestas Parciales

* **Requisito del profesor:**  
  Almacenar comprobantes sin duplicados y conservar registros locales aunque el servidor retorne solo una página parcial de movimientos.
* **Archivo y ruta exacta:**  
  `android/app/src/main/java/com/franco/billeteradistribuida/datos/Daos.kt`
* **Fragmento concreto a capturar:**
  ```kotlin
  @Dao
  interface MovimientoDao {
      @Insert(onConflict = OnConflictStrategy.REPLACE)
      suspend fun guardarMovimientos(movimientos: List<MovimientoEntity>)

      @Insert(onConflict = OnConflictStrategy.REPLACE)
      suspend fun guardarMovimiento(movimiento: MovimientoEntity)
  ...
  ```
* **Líneas aproximadas:**  
  `Daos.kt`: Líneas 9–18
* **Pasos que TÚ debes ejecutar:**  
  Abrir `Daos.kt` en Android Studio y resaltar la anotación `@Insert(onConflict = OnConflictStrategy.REPLACE)`.
* **Resultado esperado:**  
  *Pendiente de ejecución por el usuario.*
* **Qué demuestra la captura:**  
  Demuestra que al recibir nuevos movimientos o páginas de historial, Room actualiza o inserta por clave primaria (`id`), evitando duplicaciones y asegurando que los registros antiguos no se borren cuando el servidor entrega una lista parcial.

---

### EVIDENCIA 3: Historial Reactivo con Flow y LazyColumn

* **Requisito del profesor:**  
  Lista desplazable (`LazyColumn`) que consuma directamente los datos de SQLite (Room) de forma reactiva sin anidamientos que congelen la app.
* **Archivos y rutas exactas:**  
  1. `android/app/src/main/java/com/franco/billeteradistribuida/datos/Daos.kt` (`observarMovimientos`)
  2. `android/app/src/main/java/com/franco/billeteradistribuida/BilleteraViewModel.kt` (`iniciarObservadorHistorial`)
  3. `android/app/src/main/java/com/franco/billeteradistribuida/PanelBilletera.kt` (`LazyColumn` en `HISTORIAL`)
* **Fragmento concreto a capturar:**
  ```kotlin
  // Daos.kt
  @Query("""
      SELECT * FROM movimientos
      WHERE cuentaId = :cuentaId
        AND usuario = :usuario
        AND (:tipo = '' OR tipo = :tipo)
        AND (:buscar = '' OR detalle LIKE '%' || :buscar || '%')
        AND (:desde = '' OR date(fecha) >= :desde)
        AND (:hasta = '' OR date(fecha) <= :hasta)
      ORDER BY id DESC
  """)
  fun observarMovimientos(...): Flow<List<MovimientoEntity>>
  ```
  ```kotlin
  // BilleteraViewModel.kt
  private fun iniciarObservadorHistorial(cuentaId: Long, usuario: String) {
      observadorHistorial?.cancel()
      observadorHistorial = viewModelScope.launch {
          movimientoDao.observarMovimientos(cuentaId, usuario, filtroTipo, busqueda, desde, hasta)
              .flowOn(Dispatchers.IO)
              .collect { listaLocal ->
                  movimientos = listaLocal.map { it.aModelo() }
              }
      }
  }
  ```
* **Líneas aproximadas:**  
  - `Daos.kt`: Líneas 25–45  
  - `BilleteraViewModel.kt`: Líneas 120–140  
  - `PanelBilletera.kt`: Líneas 170–210
* **Pasos que TÚ debes ejecutar:**  
  1. En el emulador, acceder a la pestaña "Historial".
  2. Aplicar un filtro de búsqueda o cambiar de fecha.
  3. Comprobar que la lista se actualiza suavemente mediante `LazyColumn`.
* **Resultado esperado:**  
  *Pendiente de ejecución por el usuario.*
* **Qué demuestra la captura:**  
  Demuestra que la interfaz no depende de variables estáticas volátiles, sino de un flujo continuo (`Flow`) emitido directamente por SQLite (Room) a través de corrutinas reactivas.

---

### EVIDENCIA 4: Aislamiento de Hilos (Dispatchers.IO y Actualización en Main)

* **Requisito del profesor:**  
  Llamadas REST y lecturas/escrituras en Room despachadas en `Dispatchers.IO`. Sincronización al hilo principal (`Dispatchers.Main` / `viewModelScope`) sin provocar ANR.
* **Archivo y ruta exacta:**  
  `android/app/src/main/java/com/franco/billeteradistribuida/BilleteraViewModel.kt`
* **Fragmento concreto a capturar:**
  ```kotlin
  private suspend fun cargarOnline(t: String) {
      val p = withContext(Dispatchers.IO) { api.perfil(t) }
      val lista = withContext(Dispatchers.IO) { api.cuentas(t, p) }
      val u = p.usuario.lowercase()

      withContext(Dispatchers.IO) {
          cuentaDao.guardarCuentas(lista.map { it.aEntidad(u) })
      }
      
      val id = cuenta?.id ?: return
      val pagina = withContext(Dispatchers.IO) {
          api.historial(t, id, desde, hasta, filtroTipo, busqueda)
      }

      withContext(Dispatchers.IO) {
          movimientoDao.guardarMovimientos(pagina.items.map { it.aEntidad(id, u) })
      }
  ...
  ```
* **Líneas aproximadas:**  
  `BilleteraViewModel.kt`: Líneas 142–175
* **Pasos que TÚ debes ejecutar:**  
  Capturar el bloque donde cada operación de red y persistencia se ejecuta explícitamente dentro de `withContext(Dispatchers.IO)`.
* **Resultado esperado:**  
  *Pendiente de ejecución por el usuario.*
* **Qué demuestra la captura:**  
  Demuestra cumplimiento con el criterio de concurrencia e hilos (4 pts), garantizando que ni el socket ni las transacciones de base de datos bloqueen el hilo de renderizado de Compose.

---

### EVIDENCIA 5: Servicio Básico con Icono Distintivo y Tipo Técnico Conservado

* **Requisito del profesor:**  
  Módulo de Pago de Servicios con registro especial en Room categorizado como "Servicio Básico", con icono distinto en el historial, conservando `PAGO_SERVICIO` como tipo técnico.
* **Archivos y rutas exactas:**  
  1. `android/app/src/main/java/com/franco/billeteradistribuida/datos/Entidades.kt` (`aEntidad`)
  2. `android/app/src/main/java/com/franco/billeteradistribuida/PanelBilletera.kt` (`MovimientoFs`)
* **Fragmento concreto a capturar:**
  ```kotlin
  // Entidades.kt
  categoriaVisual = if (tipo == "PAGO_SERVICIO") "Servicio Básico" else tipo
  ```
  ```kotlin
  // PanelBilletera.kt
  @Composable
  private fun MovimientoFs(m: Movimiento, enabled: Boolean, abrir: () -> Unit) {
      val esServicioBasico = m.tipo == "PAGO_SERVICIO"
      BloqueFs {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  if (esServicioBasico) {
                      Surface(color = Color(0xFFFFF3CD), shape = RoundedCornerShape(6.dp)) {
                          Text(
                              text = "⚡ Servicio Básico",
                              color = Color(0xFF856404),
                              fontSize = 11.sp,
                              fontWeight = FontWeight.Bold,
                              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                          )
                      }
                  } else {
                      Text(if (m.ingreso) "Ingreso" else "Egreso", color = GrisFs, fontSize = 12.sp)
                  }
              }
              ...
  ```
* **Líneas aproximadas:**  
  - `Entidades.kt`: Línea 72  
  - `PanelBilletera.kt`: Líneas 390–425
* **Pasos que TÚ debes ejecutar:**  
  1. Realizar un pago de servicio (ej. LUZ o AGUA con un código de contrato).
  2. Ir a la pestaña "Historial".
  3. Tomar captura de la tarjeta del movimiento mostrando la insignia amarilla con icono "⚡ Servicio Básico" y el detalle con la referencia.
* **Resultado esperado:**  
  *Pendiente de ejecución por el usuario.*
* **Qué demuestra la captura:**  
  Demuestra que la operación adicional de pago de servicios se identifica visualmente como "Servicio Básico" con icono distintivo, mientras en la base de datos y en la lógica REST se conserva el tipo `PAGO_SERVICIO`.

---

### EVIDENCIA 6: Botones y Diálogos de Confirmación

* **Requisito del profesor:**  
  Operaciones de Depósito, Retiro, Transferencia y Pago de Servicios con cuadros de diálogo para ingresar datos y confirmación del titular.
* **Archivo y ruta exacta:**  
  `android/app/src/main/java/com/franco/billeteradistribuida/PanelBilletera.kt` (`FormularioOperacion` y `AlertDialog`)
* **Fragmento concreto a capturar:**
  ```kotlin
  // Diálogo de confirmación del titular antes de enviar dinero
  vm.beneficiario?.let { b ->
      AlertDialog(
          onDismissRequest = { vm.descartarValidacion() },
          title = { Text("Confirma el destinatario") },
          text = {
              Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                  Text("Titular", color = GrisFs)
                  Text(b.titular, fontWeight = FontWeight.Bold)
                  Text("Cuenta: ${b.numero}")
                  Text("Monto: ${dinero(b.monto)}", fontWeight = FontWeight.Bold)
              ...
  ```
* **Líneas aproximadas:**  
  `PanelBilletera.kt`: Líneas 220–255 y 330–375
* **Pasos que TÚ debes ejecutar:**  
  1. En el emulador, ir a "Operaciones".
  2. Seleccionar "Transferir" o "Depositar", ingresar una cuenta de 10 dígitos y pulsar "Consultar destinatario".
  3. Tomar captura del cuadro modal que muestra el nombre del titular destino antes de confirmar el débito.
* **Resultado esperado:**  
  *Pendiente de ejecución por el usuario.*
* **Qué demuestra la captura:**  
  Demuestra seguridad transaccional fintech: se valida y presenta la identidad del receptor mediante token temporal antes de emitir la transferencia.

---

### EVIDENCIA 7: Historial sin Conexión (Modo Offline con Privacidad)

* **Requisito del profesor:**  
  Consulta de datos guardados sin internet. Respeto a la privacidad del usuario: no permitir acceso ingresando únicamente el nombre de usuario ni guardar contraseñas en texto plano.
* **Archivos y rutas exactas:**  
  1. `android/app/src/main/java/com/franco/billeteradistribuida/datos/SeguridadLocal.kt`
  2. `android/app/src/main/java/com/franco/billeteradistribuida/BilleteraViewModel.kt` (`ingresar`)
  3. `android/app/src/main/java/com/franco/billeteradistribuida/PanelBilletera.kt` (Banner offline)
* **Fragmento concreto a capturar:**
  ```kotlin
  // BilleteraViewModel.kt (Manejo de IOException en login)
  } catch (e: IOException) {
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
      }
  ...
  ```
* **Líneas aproximadas:**  
  `BilleteraViewModel.kt`: Líneas 190–225
* **Pasos que TÚ debes ejecutar:**  
  1. Iniciar sesión previamente con internet para que Room sincronice la cuenta.
  2. Detener el servidor FastAPI (o activar Modo Avión en el emulador).
  3. Abrir la app e ingresar con el usuario y contraseña.
  4. Verificar que la app abre en modo lectura mostrando el historial local y el banner:  
     `"📶 Modo sin conexión (Room SQLite)"` junto con la fecha/hora de la última sincronización.
  5. Intentar hacer una transferencia y comprobar que el sistema lo rechaza indicando que requiere conexión activa.
* **Resultado esperado:**  
  *Pendiente de ejecución por el usuario.*
* **Qué demuestra la captura:**  
  Demuestra persistencia offline real y cumplimiento estricto de privacidad bancaria: los datos locales solo se desbloquean comprobando la contraseña con salt y hash SHA-256 local, y las transacciones monetarias están protegidas contra fraudes offline.

---

### EVIDENCIA 8: Historial Antes y Después de Reiniciar la Aplicación

* **Requisito del profesor:**  
  El historial debe sobrevivir al cierre forzoso o reinicio de la aplicación, recargándose desde Room SQLite.
* **Pasos que TÚ debes ejecutar:**  
  1. **Captura 8A:** En el emulador con la app abierta, tomar captura del historial con varios movimientos (depósitos, retiros, transferencias).
  2. Salir de la app, abrir las aplicaciones recientes de Android y deslizar hacia arriba para forzar el cierre completo del proceso.
  3. Volver a abrir la aplicación.
  4. Ingresar nuevamente (con o sin internet).
  5. **Captura 8B:** Tomar captura de la pantalla mostrando exactamente los mismos movimientos recuperados desde Room SQLite.
* **Resultado esperado:**  
  *Pendiente de ejecución por el usuario.*
* **Qué demuestra la captura:**  
  Demuestra que la persistencia no es en memoria volátil (RAM), sino almacenada de manera duradera en el archivo SQLite interno de Android vía Room.

---

### EVIDENCIA 9: Separación Estricta de Usuarios y Cuentas

* **Requisito del profesor:**  
  Movimientos y comprobantes separados por usuario y cuenta; un usuario no debe ver movimientos de otro usuario.
* **Archivos y rutas exactas:**  
  1. `android/app/src/main/java/com/franco/billeteradistribuida/datos/Daos.kt`
  2. `android/app/src/main/java/com/franco/billeteradistribuida/datos/Entidades.kt`
* **Fragmento concreto a capturar:**
  ```kotlin
  @Query("""
      SELECT * FROM movimientos
      WHERE cuentaId = :cuentaId
        AND usuario = :usuario
        ...
  """)
  fun observarMovimientos(cuentaId: Long, usuario: String, ...): Flow<List<MovimientoEntity>>
  ```
* **Líneas aproximadas:**  
  `Daos.kt`: Líneas 25–40
* **Pasos que TÚ debes ejecutar:**  
  1. Iniciar sesión con Usuario A, hacer un movimiento y cerrar sesión.
  2. Iniciar sesión con Usuario B (o registrar uno nuevo).
  3. Ir a "Historial" del Usuario B y verificar que NO aparecen los movimientos del Usuario A.
  4. Tomar capturas comparativas de ambos historiales.
* **Resultado esperado:**  
  *Pendiente de ejecución por el usuario.*
* **Qué demuestra la captura:**  
  Demuestra aislamiento de datos multitenant: la cláusula `WHERE cuentaId = :cuentaId AND usuario = :usuario` garantiza estanqueidad entre usuarios y entre cuentas del mismo usuario.

---

### EVIDENCIA 10: WebSocket en Tiempo Real (Recepción P2P sin Recargar)

* **Requisito del profesor:**  
  El emisor envía dinero por REST. El receptor (con la app abierta) recibe la notificación y actualiza su saldo en tiempo real vía WebSocket sin tocar la pantalla.
* **Archivos y rutas exactas:**  
  1. `android/app/src/main/java/com/franco/billeteradistribuida/BilleteraViewModel.kt` (`iniciarTiempoReal`)
  2. `backend/realtime.py` y `backend/main.py` (`/ws`)
* **Fragmento concreto a capturar:**
  ```kotlin
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
  ```
* **Pasos que TÚ debes ejecutar:**  
  1. Abrir la app en el emulador con la cuenta receptora (indicador en verde: "En vivo").
  2. Desde Postman o terminal de tu PC, enviar una transferencia REST hacia el número de cuenta de la app móvil.
  3. Grabar un video corto o tomar capturas secuenciales mostrando cómo el saldo de la tarjeta y la lista de movimientos aumentan instantáneamente sin pulsar "Actualizar datos".
* **Resultado esperado:**  
  *Pendiente de ejecución por el usuario.*
* **Qué demuestra la captura:**  
  Demuestra comunicación bidireccional asíncrona mediante WebSockets en un sistema distribuido real.

