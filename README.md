# REDXela - Base funcional 0.1

Java 21 + React. No es la entrega final. Leer docs/REQUISITOS.md primero.

## Docker
Desde la carpeta que contiene compose.yaml:

```bash
docker compose up --build
```
Abrir http://localhost:5173. API http://localhost:8090/api/state.
Detener con Ctrl+C y docker compose down. Las ordenes estan en memoria y se pierden al reiniciar.
El navegador debe estar en la misma PC por la URL localhost del frontend.

## Java local (Windows PowerShell, Debian o Ubuntu)
Primera terminal, desde backend:

```bash
java -version
javac -version
mkdir out
javac -d out src/Main.java
java -cp out Main
```
Segunda terminal, desde frontend (Node 22 o 24):

```bash
npm install
npm run dev
```
Abrir la URL que indique Vite. La API usa 8090; el frontend normalmente 5173.

## Prueba inicial
Crear 8 pedidos de 20 unidades. Se procesan 6 y 2 esperan.
Cambiar politica mientras hay cola y observar el orden.
Activar generador: aparecen niveles del 1 al 5. Detenerlo y esperar el vaciado.
El ordenamiento no es expropiativo. La cola y el historial no tienen limite fijo;
la memoria de la PC si es finita y debe gestionarse en la version final.

## Siguiente paso
Implementar catalogo, tipos, inventario y almacenamiento antes de sumar cuentas,
PostgreSQL y demostracion de deadlock. Ver el plan y la matriz de pendientes.
