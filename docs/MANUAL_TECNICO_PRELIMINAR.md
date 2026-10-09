# Manual tecnico preliminar - version 0.1
Java expone HTTP con HttpServer del JDK. No requiere Spring, Maven ni dependencias.
React obtiene snapshots cada 700 ms. El backend actualiza estados sin depender
 del navegador: los hilos Java ejecutan el trabajo simulado.

ScheduledExecutorService ejecuta planificador y generador. ExecutorService de
seis trabajadores permite seis pedidos simultaneos. Semaphore con seis permisos
representa capacidad de empaque; tryAcquire evita bloquear el planificador.
La cola se ordena por prioridad, llegada o unidades. No hay expropiacion.
CopyOnWriteArrayList facilita snapshots, pero no es adecuada para volumen alto:
la version completa debera usar un registro protegido y snapshots paginados.
AtomicLong produce IDs unicos durante una ejecucion; no es identidad persistente.
volatile publica cambios de estado y tiempos. El planificador es el unico que
asigna pedidos; finally garantiza liberar permisos aun ante interrupciones.
La duracion base es unidades * 500 ms, y Thread.sleep modela trabajo, no hace
operaciones de almacen reales. La base no demuestra todavia IPC entre areas.

POST valida nivel 1-5, unidades 1-1000 y politicas. GET /api/state consulta.
POST /api/orders?level=3&units=20 crea.
POST /api/policy?value=FIFO cambia.
POST /api/generator?enabled=true activa.
CORS abierto sirve al desarrollo local; falta autenticacion en esta base.

Docker: imagen Java 21 compila backend; Node compila React; Nginx sirve los assets.
No hay volumen porque todavia no hay persistencia. La API local esta en 8090.

Limitaciones: estado no transaccional entre snapshots, sin inventario ni deadlock;
metricas basicas, espera solo sobre completados, historial en memoria sin paginacion.
La revision de requisitos detalla la arquitectura final y las pruebas necesarias.
