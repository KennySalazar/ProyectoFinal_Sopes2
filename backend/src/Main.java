import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Base educativa: planificacion y estaciones reales; otros recursos son pendientes. */
public class Main {
    static final AtomicLong ids = new AtomicLong();
    static final List<Order> orders = new CopyOnWriteArrayList<>();
    static final Semaphore packing = new Semaphore(6, true);
    static final ExecutorService workers = Executors.newFixedThreadPool(6);
    static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    static volatile String policy = "PRIORIDAD";
    static volatile boolean automatic = false;
    static class Order {
        final long id = ids.incrementAndGet(), created = System.currentTimeMillis();
        final int level, units;
        volatile String state = "EN_ESPERA";
        volatile long started, finished;
        Order(int level, int units) { this.level = level; this.units = units; }
        int progress() { return started == 0 ? 0 : (int)Math.min(100, (System.currentTimeMillis()-started)*100/(units*500L)); }
        String json() { return "{\"id\":"+id+",\"level\":"+level+",\"units\":"+units+",\"state\":\""+state+"\",\"progress\":"+progress()+",\"remainingMs\":"+(started==0?units*500L:Math.max(0,units*500L-(System.currentTimeMillis()-started)))+"}"; }
    }
    // Un unico planificador cambia EN_ESPERA a PROCESANDO y evita dobles asignaciones.
    static void dispatch() {
        var waiting = orders.stream().filter(o -> o.state.equals("EN_ESPERA")).sorted(comparator()).toList();
        for (Order o : waiting) {
            if (!packing.tryAcquire()) break;
            o.state="PROCESANDO"; o.started=System.currentTimeMillis();
            workers.submit(() -> {
                try { Thread.sleep(o.units*500L); o.finished=System.currentTimeMillis(); o.state="COMPLETADO"; }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); o.state="INTERRUMPIDO"; }
                finally { packing.release(); }
            });
        }
    }
    static Comparator<Order> comparator() {
        if (policy.equals("UNIDADES")) return Comparator.comparingInt((Order o)->o.units).thenComparingLong(o->o.created);
        if (policy.equals("FIFO")) return Comparator.comparingLong(o->o.created);
        return Comparator.comparingInt((Order o)->o.level).thenComparingLong(o->o.created);
    }
    static Map<String,String> query(HttpExchange e) {
        Map<String,String> m=new HashMap<>(); String q=e.getRequestURI().getRawQuery();
        if(q!=null) for(String pair:q.split("&")) {String[] kv=pair.split("=",2);if(kv.length==2)m.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8));} return m;
    }
    static void reply(HttpExchange e,int code,String body) throws java.io.IOException {
        e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(code,bytes.length);e.getResponseBody().write(bytes);e.close();
    }
    static void handle(HttpExchange e) throws java.io.IOException {
        e.getResponseHeaders().set("Access-Control-Allow-Origin","*");
        e.getResponseHeaders().set("Access-Control-Allow-Methods","GET, POST, OPTIONS");
        if(e.getRequestMethod().equals("OPTIONS")){e.sendResponseHeaders(204,-1);e.close();return;}
        try {
            String path=e.getRequestURI().getPath();var q=query(e);
            if(path.equals("/api/state") && e.getRequestMethod().equals("GET")) {
                long done=orders.stream().filter(o->o.state.equals("COMPLETADO")).count();
                double wait=orders.stream().filter(o->o.finished>0).mapToLong(o->o.started-o.created).average().orElse(0);
                double process=orders.stream().filter(o->o.finished>0).mapToLong(o->o.finished-o.started).average().orElse(0);
                String all=String.join(",",orders.stream().sorted(comparator()).map(Order::json).toList());
                reply(e,200,"{\"policy\":\""+policy+"\",\"automatic\":"+automatic+",\"availablePacking\":"+packing.availablePermits()+",\"completed\":"+done+",\"averageWaitMs\":"+wait+",\"averageProcessMs\":"+process+",\"orders\":["+all+"]}");return;
            }
            if(!e.getRequestMethod().equals("POST")){reply(e,405,"{\"error\":\"Usa POST\"}");return;}
            switch(path) {
                case "/api/orders" -> {int level=Integer.parseInt(q.getOrDefault("level","3")), units=Integer.parseInt(q.getOrDefault("units","5"));if(level<1||level>5||units<1||units>1000)throw new IllegalArgumentException("Nivel 1-5 y unidades 1-1000");Order o=new Order(level,units);orders.add(o);reply(e,201,o.json());}
                case "/api/policy" -> {String v=q.getOrDefault("value","");if(!List.of("PRIORIDAD","FIFO","UNIDADES").contains(v))throw new IllegalArgumentException("Politica invalida");policy=v;reply(e,200,"{}");}
                case "/api/generator" -> {automatic=Boolean.parseBoolean(q.getOrDefault("enabled","false"));reply(e,200,"{}");}
                default -> reply(e,404,"{\"error\":\"Ruta no encontrada\"}");
            }
        } catch(IllegalArgumentException ex) {reply(e,400,"{\"error\":\"Parametros invalidos\"}");}
        catch(Exception ex) {ex.printStackTrace();reply(e,500,"{\"error\":\"Error interno\"}");}
    }
    public static void main(String[] args) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress(8090),0);
        server.createContext("/api",Main::handle);server.setExecutor(Executors.newCachedThreadPool());server.start();
        scheduler.scheduleWithFixedDelay(Main::dispatch,0,100,TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(()->{if(automatic)orders.add(new Order(ThreadLocalRandom.current().nextInt(1,6),ThreadLocalRandom.current().nextInt(1,21)));},0,1,TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);scheduler.shutdownNow();workers.shutdownNow();}));
        System.out.println("REDXela API: http://localhost:8090/api/state");
    }
}
