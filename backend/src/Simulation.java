import java.util.*;
import java.util.concurrent.*;

/** Motor concurrente. El monitor protege estados, asignaciones y snapshots atomicos. */
public final class Simulation implements AutoCloseable {
    public record Product(int id, String name, String type) { }
    public record Line(int productId, int units) { }
    private record Stage(String name, int msPerUnit, List<String> needs) { }
    private static final class Resource {
        final String key, name;
        final Semaphore permits;
        final long[] owners, since;
        long busyNanos;
        Resource(String key, String name, int capacity) {
            this.key=key; this.name=name; permits=new Semaphore(capacity, true);
            owners=new long[capacity]; since=new long[capacity];
        }
    }
    private static final class Order {
        final long id, created=System.nanoTime();
        final int level, units;
        final List<Line> lines;
        final List<Stage> stages;
        int stageIndex;
        String state="EN_ESPERA", reason="Esperando planificador";
        long waitingSince=created, stageStarted, finished, waitNanos, processNanos;
        int finishedEstimatedMs;
        List<Resource> held=List.of();
        Order(long id, int level, List<Line> lines, List<Stage> stages) {
            this.id=id; this.level=level; this.lines=List.copyOf(lines); this.stages=stages;
            units=lines.stream().mapToInt(Line::units).sum();
        }
        Stage stage() { return stages.get(stageIndex); }
        long estimateMs() { return stages.stream().mapToLong(s -> (long)s.msPerUnit*units).sum(); }
    }
    private final Object gate=new Object();
    private final List<Order> orders=new ArrayList<>();
    private final LinkedHashMap<String,Resource> resources=new LinkedHashMap<>();
    private final List<Product> products=List.of(
        new Product(1,"Cuaderno","GENERAL"), new Product(2,"Vaso de vidrio","FRAGIL"),
        new Product(3,"Saco de cemento","PESADO"), new Product(4,"Silla de oficina","VOLUMINOSO"),
        new Product(5,"Audifonos","ELECTRONICO"), new Product(6,"Camiseta","GENERAL"),
        new Product(7,"Espejo","FRAGIL"), new Product(8,"Motor industrial","PESADO"),
        new Product(9,"Mesa","VOLUMINOSO"), new Product(10,"Teclado","ELECTRONICO"));
    // Combinaciones distintas; el despacho agrega recursos comunes a todos los tipos.
    private final Map<String,List<String>> typeNeeds=Map.of(
        "GENERAL",List.of("BODEGA","EMPAQUE"),
        "FRAGIL",List.of("BODEGA","EMPAQUE","CALIDAD"),
        "PESADO",List.of("BODEGA","MONTACARGAS","CARGA"),
        "VOLUMINOSO",List.of("BODEGA","MONTACARGAS","EMPAQUE","CARGA"),
        "ELECTRONICO",List.of("BODEGA","CALIDAD","ESCANER"));
    private final ExecutorService workers=Executors.newFixedThreadPool(16);
    private final ScheduledExecutorService timers=Executors.newScheduledThreadPool(2);
    private final long born=System.nanoTime();
    private final int timeScale;
    private String policy="PRIORIDAD";
    private boolean automatic, closed;
    private long nextId;
    public Simulation() { this(1); }
    // Escala de pruebas; la aplicacion usa 1. No representa aun un reloj SLA virtual.
    Simulation(int timeScale) {
        this.timeScale=timeScale;
        addResource("BODEGA","Personal de bodega",10);
        addResource("MONTACARGAS","Montacargas",3);
        addResource("EMPAQUE","Estaciones de empaque",6);
        addResource("CALIDAD","Control de calidad",4);
        addResource("ESCANER","Escaner compartido",1);
        addResource("CARGA","Areas de carga",2);
    }
    private void addResource(String key,String name,int count) {resources.put(key,new Resource(key,name,count));}
    public void start() {
        timers.scheduleWithFixedDelay(this::tick,0,50,TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(()->{
            synchronized(gate) {
                if(automatic && !closed) {
                    var random=ThreadLocalRandom.current();
                    create(random.nextInt(1,6),List.of(new Line(random.nextInt(1,11),random.nextInt(1,13))));
                }
            }
        },0,1,TimeUnit.SECONDS);
    }
    public Map<String,Object> create(int level,List<Line> input) {
        synchronized(gate) {
            if(closed)throw new IllegalStateException("Simulacion cerrada");
            if(level<1 || level>5)throw new IllegalArgumentException("Nivel permitido: 1 a 5");
            if(input.isEmpty() || input.size()>10)throw new IllegalArgumentException("Selecciona entre 1 y 10 lineas");
            Map<Integer,Integer> merged=new LinkedHashMap<>();
            for(Line l:input) {
                product(l.productId);
                if(l.units<1 || l.units>1000)throw new IllegalArgumentException("Unidades permitidas: 1 a 1000");
                merged.merge(l.productId,l.units,Integer::sum);
            }
            int total=merged.values().stream().mapToInt(Integer::intValue).sum();
            if(total>1000)throw new IllegalArgumentException("Maximo 1000 unidades por pedido");
            List<Line> lines=merged.entrySet().stream().map(e->new Line(e.getKey(),e.getValue())).toList();
            Order o=new Order(++nextId,level,lines,stagesFor(lines));orders.add(o);
            return orderSnapshot(o,System.nanoTime());
        }
    }
    private Product product(int id) {return products.stream().filter(p->p.id==id).findFirst().orElseThrow(()->new IllegalArgumentException("Producto inexistente"));}
    private List<Stage> stagesFor(List<Line> lines) {
        Set<String> needs=new HashSet<>();
        for(Line l:lines)needs.addAll(typeNeeds.get(product(l.productId).type));
        List<Stage> stages=new ArrayList<>();
        stages.add(new Stage("PREPARACION",150,needs.contains("MONTACARGAS")?List.of("BODEGA","MONTACARGAS"):List.of("BODEGA")));
        if(needs.contains("EMPAQUE"))stages.add(new Stage("EMPAQUE",250,List.of("BODEGA","EMPAQUE")));
        if(needs.contains("CALIDAD"))stages.add(new Stage("CALIDAD",180,List.of("BODEGA","CALIDAD")));
        // El escaneo termina antes de cargar: no monopoliza el escaner durante la carga.
        stages.add(new Stage("DESPACHO_ESCANEO",40,List.of("BODEGA","ESCANER")));
        stages.add(new Stage("DESPACHO_CARGA",80,needs.contains("MONTACARGAS")?List.of("BODEGA","MONTACARGAS","CARGA"):List.of("BODEGA","CARGA")));
        return List.copyOf(stages);
    }
    public void setPolicy(String value) {
        if(!List.of("PRIORIDAD","FIFO","UNIDADES").contains(value))throw new IllegalArgumentException("Politica invalida");
        synchronized(gate){policy=value;}
    }
    public void setAutomatic(boolean enabled){synchronized(gate){automatic=enabled;}}
    private Comparator<Order> comparator() {
        Comparator<Order> c=switch(policy) {
            case "FIFO" -> Comparator.comparingLong(o->o.created);
            case "UNIDADES" -> Comparator.comparingInt(o->o.units);
            default -> Comparator.comparingInt(o->o.level);
        };
        return c.thenComparingLong(o->o.created).thenComparingLong(o->o.id);
    }
    /** Todos los recursos de la etapa se toman o ninguno. Nunca retenemos un subconjunto. */
    void tick() {
        synchronized(gate) {
            if(closed)return;
            List<Order> waiting=orders.stream().filter(o->o.state.equals("EN_ESPERA")).sorted(comparator()).toList();
            for(Order o:waiting) {
                List<Resource> needed=o.stage().needs.stream().map(resources::get).toList();
                List<String> missing=needed.stream().filter(r->r.permits.availablePermits()==0).map(r->r.name).toList();
                if(!missing.isEmpty()){o.reason="Sin disponibilidad: "+String.join(", ",missing);continue;}
                long now=System.nanoTime();
                for(Resource r:needed) {
                    if(!r.permits.tryAcquire())throw new IllegalStateException("Permisos inconsistentes");
                    for(int i=0;i<r.owners.length;i++)if(r.owners[i]==0){r.owners[i]=o.id;r.since[i]=now;break;}
                }
                o.held=needed;o.waitNanos+=now-o.waitingSince;o.stageStarted=now;
                o.state="PROCESANDO";o.reason="";
                try{workers.submit(()->runStage(o));}
                catch(RejectedExecutionException ex){release(o);o.state="INTERRUMPIDO";o.reason="Motor detenido";}
            }
        }
    }
    private void runStage(Order o) {
        boolean success=false;
        try {
            Thread.sleep(Math.max(1,(long)o.stage().msPerUnit*o.units/timeScale));success=true;
        } catch(InterruptedException ex){Thread.currentThread().interrupt();}
        finally {
            synchronized(gate) {
                long now=System.nanoTime();o.processNanos+=now-o.stageStarted;
                release(o);o.stageStarted=0;
                if(success && !closed) {
                    o.finishedEstimatedMs+=o.stage().msPerUnit*o.units;
                    o.stageIndex++;
                    if(o.stageIndex==o.stages.size()){o.state="COMPLETADO";o.finished=now;}
                    else{o.state="EN_ESPERA";o.waitingSince=now;o.reason="Esperando recursos de "+o.stage().name;}
                } else {o.state="INTERRUMPIDO";o.reason="Ejecucion detenida";}
            }
        }
    }
    private void release(Order o) {
        long now=System.nanoTime();
        for(Resource r:o.held) {
            for(int i=0;i<r.owners.length;i++)if(r.owners[i]==o.id){r.busyNanos+=now-r.since[i];r.owners[i]=0;r.since[i]=0;break;}
            r.permits.release();
        }
        o.held=List.of();
    }
    private Map<String,Object> orderSnapshot(Order o,long now) {
        long elapsed=o.stageStarted==0?0:(now-o.stageStarted)/1_000_000*timeScale;
        long estimated=o.estimateMs();
        long worked=o.finishedEstimatedMs;
        if(o.stageStarted>0)worked+=Math.min(elapsed,(long)o.stage().msPerUnit*o.units);
        long waiting=o.waitNanos+(o.state.equals("EN_ESPERA")?now-o.waitingSince:0);
        List<Map<String,Object>> lines=o.lines.stream().map(l->Map.<String,Object>of("productId",l.productId,"name",product(l.productId).name,"type",product(l.productId).type,"units",l.units)).toList();
        Map<String,Object> s=new LinkedHashMap<>();
        s.put("id",o.id);s.put("level",o.level);s.put("units",o.units);s.put("lines",lines);
        s.put("state",o.state);s.put("stage",o.state.equals("COMPLETADO")?"FINALIZADO":o.stage().name);
        s.put("reason",o.reason);s.put("progress",Math.min(100,worked*100/estimated));
        s.put("remainingMs",Math.max(0,estimated-worked));s.put("waitMs",waiting/1_000_000);
        s.put("heldResources",o.held.stream().map(r->r.key).toList());
        return s;
    }
    public Map<String,Object> snapshot() {
        synchronized(gate) {
            long now=System.nanoTime();List<Order> done=orders.stream().filter(o->o.state.equals("COMPLETADO")).toList();
            Map<String,Object> s=new LinkedHashMap<>();
            s.put("version","0.2");s.put("policy",policy);s.put("automatic",automatic);
            s.put("total",orders.size());s.put("completed",done.size());
            s.put("activeCount",orders.stream().filter(o->o.state.equals("PROCESANDO")).count());
            s.put("waitingCount",orders.stream().filter(o->o.state.equals("EN_ESPERA")).count());
            s.put("averageWaitMs",done.stream().mapToLong(o->o.waitNanos/1_000_000).average().orElse(0));
            s.put("averageProcessMs",done.stream().mapToLong(o->o.processNanos/1_000_000).average().orElse(0));
            List<Map<String,Object>> resourceData=new ArrayList<>();
            for(Resource r:resources.values()) {
                long busy=r.busyNanos;List<Map<String,Object>> slots=new ArrayList<>();
                for(int i=0;i<r.owners.length;i++) {
                    if(r.owners[i]>0)busy+=now-r.since[i];
                    slots.add(Map.of("slot",i+1,"owner",r.owners[i],"state",r.owners[i]==0?"DISPONIBLE":"EN_USO"));
                }
                resourceData.add(Map.of("key",r.key,"name",r.name,"capacity",r.owners.length,"available",r.permits.availablePermits(),"slots",slots,"occupancyPercent",busy*100.0/(Math.max(1,now-born)*r.owners.length)));
            }
            s.put("resources",resourceData);
            s.put("products",products.stream().map(v->Map.of("id",v.id,"name",v.name,"type",v.type)).toList());
            s.put("types",typeNeeds.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->Map.of("name",e.getKey(),"needs",e.getValue(),"description",description(e.getKey()))).toList());
            s.put("active",orders.stream().filter(o->o.state.equals("PROCESANDO")).map(o->orderSnapshot(o,now)).toList());
            s.put("waiting",orders.stream().filter(o->o.state.equals("EN_ESPERA")).sorted(comparator()).map(o->orderSnapshot(o,now)).toList());
            // Historial visible acotado; el total y los promedios conservan toda la corrida.
            s.put("history",orders.stream().filter(o->o.state.equals("COMPLETADO")||o.state.equals("INTERRUMPIDO")).sorted(Comparator.comparingLong((Order o)->o.id).reversed()).limit(100).map(o->orderSnapshot(o,now)).toList());
            return s;
        }
    }
    private String description(String type) {
        return switch(type) {
            case "GENERAL"->"Productos livianos de manejo comun";
            case "FRAGIL"->"Requiere empaque y revision para evitar roturas";
            case "PESADO"->"Requiere montacargas; traslado sin empaque en estacion";
            case "VOLUMINOSO"->"Requiere montacargas y estacion de empaque";
            default->"Requiere control funcional y trazabilidad por escaner";
        };
    }
    @Override public void close() {
        synchronized(gate){closed=true;automatic=false;}
        timers.shutdownNow();workers.shutdownNow();
        try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
    }
}
