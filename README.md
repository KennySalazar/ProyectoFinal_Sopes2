# REDXela - Proyecto final de Sistemas Operativos 2

REDXela simula un centro de distribucion con pedidos concurrentes, recursos
limitados, recepcion de mercancia, inventario y almacenamiento. El backend usa
hilos y sincronizacion real en Java. El frontend permite observar y controlar
la simulacion. PostgreSQL conserva la corrida y Docker simplifica el arranque.

## Tecnologias y versiones

| Componente | Version o configuracion |
| --- | --- |
| Backend | Java 21, imagen eclipse-temurin:21-jdk |
| Servidor HTTP | HttpServer del JDK; no utiliza Spring Boot |
| Frontend | React y React DOM 19.1.1 |
| Herramienta de construccion | Vite 6.3.6 |
| Node en Docker | node:24-alpine |
| Base de datos | postgres:18-alpine |
| Controlador JDBC | PostgreSQL JDBC 42.7.14, incluido en backend/lib |
| Servidor de archivos web | nginx:alpine; etiqueta sin version fija |
| Contenedores | Docker y Docker Compose v2; no se fija una version puntual |
| Version funcional actual | 0.7 / frontend 0.7.0 |

Las etiquetas Docker por version mayor reciben actualizaciones; el proyecto
no fija digest ni parche exacto de esas imagenes. package-lock.json fija las
dependencias del frontend. Java compila con javac; no usa Maven ni Gradle.

## Evolucion del proyecto

| Version | Funcionalidad incorporada |
| --- | --- |
| 0.1 | Base Java/React, pedidos, recursos y planificador |
| 0.2 | Catalogo, cinco tipos de mercancia y etapas por recurso |
| 0.3 | Recepcion, inventario, reservas, ubicaciones, PostgreSQL y Docker |
| 0.4 | Interbloqueo real controlado y resolucion manual; retiro del aviso de etapas |
| 0.5 | Registro de clientes, servicio contratado y servicio historico por pedido |
| 0.6 | Envejecimiento, prioridad efectiva e intervalo configurable |
| 0.7 | Reloj SLA, cumplimiento por servicio y comparacion reproducible |
| Entrega documental | Manuales finales con caratula y guia de calificacion; motor sigue en 0.7 |

## Estructura

- backend/src: motor, API, persistencia, almacen y comparacion.
- backend/tests: pruebas Java.
- backend/db: esquema de checkpoint y vistas SQL.
- backend/lib: controlador PostgreSQL y licencia.
- frontend/src: interfaz React y estilos.
- compose.yaml: backend, frontend, PostgreSQL y volumen.
- docs: manual de usuario, manual tecnico, guia de calificacion y capturas.

## Levantar con Docker

Requisito: Docker activo y comando docker compose disponible. No necesita
instalar Java, Node ni PostgreSQL en el host para esta modalidad.

Desde la carpeta redxela, donde esta compose.yaml:

```bash
docker compose up --build
```

La primera ejecucion requiere conexion para descargar imagenes y dependencias.
Abra http://localhost:5173 en la misma computadora. API: http://localhost:8090/api/state.

Para ejecutarlo en segundo plano y revisar servicios:

```bash
docker compose up --build -d
docker compose ps
docker compose logs backend
docker compose logs db
```

PostgreSQL: localhost:5439, base redxela, usuario redxela y clave local
predeterminada redxela_local. Dentro de Compose el backend conecta a db:5432.
Puede copiar .env.example a .env y configurar DATABASE_PASSWORD antes de crear
la base por primera vez. Cambiar .env no cambia la clave de una base ya existente.

## Detener, reiniciar y actualizar conservando datos

```bash
docker compose down
docker compose up --build
```

Mantenga la misma carpeta y proyecto Compose para reutilizar el volumen.
No use docker compose down -v si necesita conservar datos: elimina el volumen.
Para actualizar, detenga servicios, respalde su carpeta, reemplace los archivos
con los de esta entrega en la misma carpeta redxela y conserve .env. Elimine los
Markdown antiguos de docs al sustituir esa carpeta; copiar encima no los borra.
Los manuales incluidos ya tienen la caratula enviada por el estudiante.

Una etapa interrumpida se repite; reservas y completados se recuperan.
El generador inicia detenido y la ocupacion comienza una nueva medicion.

## Primera operacion

1. Abra Clientes y registre una cuenta con servicio.
2. Para demostrar todos los niveles del generador, registre una cuenta por cada nivel.
3. Cree un pedido de 20 sacos de cemento cuando no haya stock de ese producto.
4. Debe esperar sin ocupar recursos. Registre una recepcion de 50 sacos.
5. Espere REGISTRADA y observe la reactivacion automatica.
6. Al completar, deben quedar 30 fisicas, 0 reservadas y 30 disponibles,
   siempre que ningun otro pedido consuma ese producto durante la prueba.
7. Consulte Recursos, Almacen e Historial para seguir el flujo.

## Recursos y politicas

Empaque 6, carga 2, bodega 10, montacargas 3, calidad 4 y escaner 1.
Almacen: 600 unidades en 60 ubicaciones de 10. El control de despacho es
un recurso auxiliar exclusivo de la demostracion de conflicto.

Politicas: PRIORIDAD, FIFO, UNIDADES y ENVEJECIMIENTO. Empates por ID.
La prioridad no interrumpe trabajo activo. Envejecimiento usa segundos reales
de espera acumulada y mantiene el servicio contratado.

## Tiempos y comparacion

La escala inicial SLA es 60 segundos simulados por segundo real. Se fija por
pedido; cambiarla afecta pedidos nuevos, sin acelerar hilos ni envejecimiento.
Limites: Expres 1 minuto adoptado por el proyecto, Prioritario 10, Estandar 30,
Programado 60 y Economico 120. Se mide espera, no tiempo total de procesamiento.
Pedidos antiguos sin escala muestran SIN_MEDICION.

Comparacion acepta 10 a 100 pedidos y semilla. Usa eventos discretos con la
misma carga para cuatro politicas, stock suficiente y reglas compartidas con
el motor. No modifica la operacion ni es un benchmark de hilos reales.
El limite de 100 no limita el generador operativo.

## Ejecucion local sin contenedores para Java y React

Requiere JDK 21, Node 24 y PostgreSQL disponible. Puede iniciar solo la base:

```bash
# Desde redxela
docker compose up -d db
# Desde redxela/backend, en bash
mkdir -p out
javac -d out src/*.java
java -cp "out:lib/*" Main
```

PowerShell, desde backend:

```powershell
New-Item -ItemType Directory -Force out
javac -d out (Get-ChildItem src/*.java).FullName
java -cp "out;lib/*" Main
```

En otra terminal, desde frontend:

```bash
npm ci
npm run dev
```

Variables del backend: DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD, PORT,
SCHEMA_PATH y SIMULATION_TIME_SCALE. Los valores predeterminados conectan a
localhost:5439/redxela. REDXELA_MODE=memory activa una corrida de prueba sin
persistencia; no se activa automaticamente ante una falla de PostgreSQL.
VITE_API_URL configura la API del frontend al compilar. No inicie simultaneamente
backend local y backend Docker en el mismo puerto ni sobre la misma corrida.

## Pruebas

Desde backend con JDK 21 y bash:

```bash
javac -d out src/*.java tests/*.java
java -cp out SimulationTest
java -cp out InventoryTest
java -cp out DeadlockTest
java -cp out ClientTest
java -cp out AgingTest
java -cp out SlaTest
java -cp out ComparisonTest
```

En PowerShell compile con:

```powershell
javac -d out (Get-ChildItem src/*.java,tests/*.java).FullName
```

PostgresIntegrationTest debe usar una base nueva y vacia, nunca redxela con
sus datos. Cree redxela_test una sola vez desde la carpeta redxela:

```bash
docker compose exec db psql -U redxela -d redxela -c "CREATE DATABASE redxela_test;"
```

Desde backend en bash:

```bash
export TEST_DATABASE_URL=jdbc:postgresql://localhost:5439/redxela_test
java -cp "out:lib/*" PostgresIntegrationTest
```

PowerShell:

```powershell
$env:TEST_DATABASE_URL="jdbc:postgresql://localhost:5439/redxela_test"
java -cp "out;lib/*" PostgresIntegrationTest
```

La prueba rechaza una base con una corrida existente. No borre datos de su
entrega para repetirla; use otra base nueva.

## Consultas para la demostracion

```bash
docker compose exec db psql -U redxela -d redxela -c "SELECT * FROM customer_accounts ORDER BY client_id;"
docker compose exec db psql -U redxela -d redxela -c "SELECT * FROM inventory_products ORDER BY product_id;"
docker compose exec db psql -U redxela -d redxela -c "SELECT * FROM receipt_events ORDER BY receipt_id;"
docker compose exec db psql -U redxela -d redxela -c "SELECT * FROM warehouse_locations WHERE used > 0 ORDER BY cell_id;"
```

Son vistas de consulta. La fuente autoritativa es un checkpoint BYTEA confirmado
junto con una proyeccion JSONB. Modifique el dominio por la interfaz/API, no SQL manual.

## Decisiones y limites

Cuentas de clientes sin login: el enunciado exige registro y servicio.
Recepcion e Inventario son hilos independientes del mismo proceso, con mensajes.
Historial visible: ultimos 100 finalizados, por ID descendente; no orden de termino.
El motor conserva toda la corrida y reescribe checkpoints completos, por lo que
la generacion sin tope fijo no implica memoria o capacidad fisica infinitas.
Si falla persistencia, se pausa el motor y se requiere restablecer la base y reiniciar.
Los manuales y la guia explican estas decisiones y los pasos de demostracion.
