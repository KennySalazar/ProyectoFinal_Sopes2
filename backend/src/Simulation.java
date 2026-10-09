import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Motor concurrente. El monitor protege estados, asignaciones y snapshots atomicos. */
public final class Simulation implements AutoCloseable {
    public record Product(int id, String name, String type) implements Serializable { }
    public record Line(int productId, int units) implements Serializable { }
    private record Stage(String name, int msPerUnit, List<String> needs) implements Serializable { }
    private static final class Client implements Serializable {
        private static final long serialVersionUID=1L;
        final long id;
        final String name,email;
        int level;
        Client(long id,String name,String email,int level){this.id=id;this.name=name;this.email=email;this.level=level;}
    }
    private final List<Client> clients=new ArrayList<>();
    private long nextClientId;
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
    private static final class Order implements Serializable {
        private static final long serialVersionUID=1L;
        final long id, created=System.nanoTime();
        final int level, units;
        long clientId;
        String clientName,clientEmail;
        final List<Line> lines;
        final List<Stage> stages;
        int stageIndex;
        String state="EN_ESPERA", reason="Esperando planificador";
        long waitingSince=created, stageStarted, finished, waitNanos, processNanos;
        int finishedEstimatedMs;
        long pendingWaitNanos;
        int slaRate;
        transient List<Resource> held=List.of();
        Map<Long,Integer> reservation;
        Order(long id, int level, List<Line> lines, List<Stage> stages) {
            this.id=id; this.level=level; this.lines=List.copyOf(lines); this.stages=stages;
            units=lines.stream().mapToInt(Line::units).sum();
        }
        Stage stage() { return stages.get(stageIndex); }
        long estimateMs() { return stages.stream().mapToLong(s -> (long)s.msPerUnit*units).sum(); }
    }
    private static final class Receipt implements Serializable {
        private static final long serialVersionUID=1L;
        final long id;
        final int productId, units;
        String state="PENDIENTE_ESCANEO", reason="Esperando escaner";
        boolean scanned;
        long lotId;
        Receipt(long id,int productId,int units){this.id=id;this.productId=productId;this.units=units;}
    }
    // Mensaje inmutable. Recepcion no toca existencias ni ubicaciones.
    private record ReceiptEvent(long receiptId,int productId,int units) { }
    private record DurableState(int format,List<Order> orders,List<Receipt> receipts,Warehouse warehouse,
                                long nextId,long nextReceiptId,String policy,List<Client> clients,long nextClientId,int agingSeconds,int clockRate,Map<String,Object> comparison) implements Serializable { }
    private final Object gate=new Object();
    private final StateStore store;
    private Warehouse warehouse=new Warehouse();
    private final List<Receipt> receipts=new ArrayList<>();
    private final BlockingQueue<ReceiptEvent> channel=new ArrayBlockingQueue<>(8);
    private final Set<Long> queuedReceipts=new HashSet<>();
    private final ExecutorService areas=Executors.newFixedThreadPool(2);
    private String persistenceError="";
    private long nextReceiptId;
    private boolean recovered;

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
    private int agingSeconds=30;
    private int clockRate=60;
    private Map<String,Object> comparison=Map.of();
    private long lastWaitCheckpoint=System.nanoTime();
    private boolean automatic, closed;
    private Order conflictA, conflictB;
    private Thread conflictThreadA, conflictThreadB;
    private String conflictState="DISPONIBLE";
    private long conflictWinner;
    private int conflictReady;
    private final List<String> conflictLog=new ArrayList<>();
    private long nextId;
    public Simulation() { this(1,StateStore.memory()); }
    // Aceleracion de procesamiento para fixtures; distinta de la escala del reloj SLA.
    Simulation(int timeScale) { this(timeScale,StateStore.memory()); }
    Simulation(int timeScale,StateStore store) {
        if(timeScale<1||timeScale>100)throw new IllegalArgumentException("Escala permitida: 1 a 100");
        this.timeScale=timeScale;this.store=store;
        addResource("BODEGA","Personal de bodega",10);
        addResource("MONTACARGAS","Montacargas",3);
        addResource("EMPAQUE","Estaciones de empaque",6);
        addResource("CALIDAD","Control de calidad",4);
        addResource("ESCANER","Escaner compartido",1);
        addResource("CARGA","Areas de carga",2);
        addResource("CONTROL_CONFLICTO","Control de despacho (demostracion)",1);
        restore();
        persist();
    }
    private void addResource(String key,String name,int count) {resources.put(key,new Resource(key,name,count));}
    public void start() {
        areas.submit(this::receptionLoop);areas.submit(this::inventoryLoop);
        timers.scheduleWithFixedDelay(()->{try{tick();}catch(RuntimeException ex){System.err.println(ex.getMessage());}},0,50,TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(()->{
            synchronized(gate) {
                if(automatic && !closed && persistenceError.isEmpty()) {
                    var random=ThreadLocalRandom.current();
                    try{if(!clients.isEmpty())createForClient(clients.get(random.nextInt(clients.size())).id,List.of(new Line(random.nextInt(1,11),random.nextInt(1,13))));}
                    catch(RuntimeException ex){System.err.println(ex.getMessage());}
                }
            }
        },0,1,TimeUnit.SECONDS);
    }
    public Map<String,Object> registerClient(String name,String email,int level) {
        synchronized(gate){
            healthy();
            if(name==null||name.strip().length()<2||name.strip().length()>100||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Nombre permitido: 2 a 100 caracteres sin caracteres de control");
            if(email==null||email.strip().length()>150||!email.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Correo invalido");
            if(level<1||level>5)throw new IllegalArgumentException("Servicio permitido: 1 a 5");
            String normalized=email.strip().toLowerCase(Locale.ROOT);
            if(clients.stream().anyMatch(c->c.email.equals(normalized)))throw new IllegalArgumentException("Ya existe un cliente con ese correo");
            Client c=new Client(++nextClientId,name.strip(),normalized,level);clients.add(c);persist();return clientSnapshot(c);
        }
    }
    private Client client(long id){return clients.stream().filter(c->c.id==id).findFirst().orElseThrow(()->new IllegalArgumentException("Cliente inexistente"));}
    private Map<String,Object> clientSnapshot(Client c){
        return Map.of("id",c.id,"name",c.name,"email",c.email,"level",c.level,"orders",orders.stream().filter(o->o.clientId==c.id).count(),"completed",orders.stream().filter(o->o.clientId==c.id&&o.state.equals("COMPLETADO")).count());
    }
    public void changeClientService(long id,int level){
        synchronized(gate){healthy();if(level<1||level>5)throw new IllegalArgumentException("Servicio permitido: 1 a 5");client(id).level=level;persist();}
    }
    public Map<String,Object> createForClient(long clientId,List<Line> input){
        synchronized(gate){healthy();Client c=client(clientId);return createOrder(c.level,input,c);}
    }
    // Entrada de fixtures anteriores; la API exige un cliente registrado.
    Map<String,Object> create(int level,List<Line> input){return createOrder(level,input,null);}
    private Map<String,Object> createOrder(int level,List<Line> input,Client client) {
        synchronized(gate) {
            healthy();
            if(level<1 || level>5)throw new IllegalArgumentException("Nivel permitido: 1 a 5");
            if(input.isEmpty() || input.size()>10)throw new IllegalArgumentException("Selecciona entre 1 y 10 lineas");
            Map<Integer,Integer> merged=new LinkedHashMap<>();
            for(Line l:input) {
                product(l.productId);
                if(l.units<1 || l.units>600)throw new IllegalArgumentException("Unidades permitidas: 1 a 600");
                merged.merge(l.productId,l.units,Integer::sum);
            }
            int total=merged.values().stream().mapToInt(Integer::intValue).sum();
            if(total>Warehouse.CELL_COUNT*Warehouse.CELL_CAPACITY)throw new IllegalArgumentException("Maximo 600 unidades por pedido: capacidad fisica del almacen");
            List<Line> lines=merged.entrySet().stream().map(e->new Line(e.getKey(),e.getValue())).toList();
            Order o=new Order(++nextId,level,lines,stagesFor(lines));o.slaRate=clockRate;associate(o,client);o.state="ESPERA_STOCK";o.reason="Evaluando existencias";orders.add(o);persist();
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
        if(!Scheduling.POLICIES.contains(value))throw new IllegalArgumentException("Politica invalida");
        synchronized(gate){healthy();policy=value;persist();}
    }
    public void setAutomatic(boolean enabled){synchronized(gate){healthy();if(enabled&&clients.isEmpty())throw new IllegalArgumentException("Registra al menos un cliente antes de iniciar el generador");automatic=enabled;}}
    public void setAgingSeconds(int seconds){
        synchronized(gate){healthy();if(seconds<1||seconds>3600)throw new IllegalArgumentException("Intervalo permitido: 1 a 3600 segundos");agingSeconds=seconds;persist();}
    }
    private boolean isWaiting(Order o){return List.of("EN_ESPERA","ESPERA_STOCK","BLOQUEADO","CONFLICTO_REINTENTO").contains(o.state);}
    private long waitingNanos(Order o,long now){return o.waitNanos+(isWaiting(o)?Math.max(0,now-o.waitingSince):0);}
    private int effectiveLevel(Order o,long now){
        if(!policy.equals("ENVEJECIMIENTO"))return o.level;
        return Scheduling.effectiveLevel(o.level,waitingNanos(o,now)/1_000_000,agingSeconds);
    }
    private Comparator<Order> comparator() {
        // Capturar un instante evita cambiar claves durante una misma ordenacion.
        long now=System.nanoTime();
        Comparator<Order> c=Comparator.comparingLong(o->Scheduling.rank(policy,o.id,o.level,o.units,waitingNanos(o,now)/1_000_000,agingSeconds));
        return c.thenComparingLong(o->o.id);
    }
    /** Todos los recursos de la etapa se toman o ninguno. Nunca retenemos un subconjunto. */
    void tick() {
        synchronized(gate) {
            if(closed||!persistenceError.isEmpty())return;
            List<Order> waiting=orders.stream().filter(o->o.state.equals("EN_ESPERA")||o.state.equals("ESPERA_STOCK")).sorted(comparator()).toList();
            boolean dirty=false;
            for(Order o:waiting) {
                if(o.reservation==null) {
                    String shortage=warehouse.shortage(o.lines);
                    if(!shortage.isEmpty()) {
                        String reason="Stock insuficiente: "+shortage;
                        dirty|=!o.state.equals("ESPERA_STOCK")||!o.reason.equals(reason);
                        o.state="ESPERA_STOCK";o.reason=reason;continue;
                    }
                    o.reservation=warehouse.reserve(o.lines);o.state="EN_ESPERA";o.reason="Esperando recursos";persist();
                }
                List<Resource> needed=o.stage().needs.stream().map(resources::get).toList();
                List<String> missing=needed.stream().filter(r->r.permits.availablePermits()==0).map(r->r.name).toList();
                if(!missing.isEmpty()){String reason="Sin disponibilidad: "+String.join(", ",missing);dirty|=!o.reason.equals(reason);o.reason=reason;continue;}
                long now=System.nanoTime();
                for(Resource r:needed) {
                    if(!r.permits.tryAcquire())throw new IllegalStateException("Permisos inconsistentes");
                    for(int i=0;i<r.owners.length;i++)if(r.owners[i]==0){r.owners[i]=o.id;r.since[i]=now;break;}
                }
                o.held=needed;o.waitNanos+=now-o.waitingSince;o.stageStarted=now;
                o.state="PROCESANDO";o.reason="";
                try{persist();workers.submit(()->runStage(o));}
                catch(RuntimeException ex){release(o);o.state="PAUSADO_PERSISTENCIA";o.reason=ex.getMessage();throw ex;}
            }
            if(dirty||(!waiting.isEmpty()&&System.nanoTime()-lastWaitCheckpoint>=5_000_000_000L))persist();
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
                if(success && !closed && persistenceError.isEmpty()) {
                    o.finishedEstimatedMs+=o.stage().msPerUnit*o.units;
                    o.stageIndex++;
                    if(o.stageIndex==o.stages.size()){warehouse.consume(o.reservation);o.reservation=Map.of();o.state="COMPLETADO";o.finished=now;}
                    else{o.state="EN_ESPERA";o.waitingSince=now;o.reason="Esperando recursos de "+o.stage().name;}
                } else {o.state=persistenceError.isEmpty()?"EN_ESPERA":"PAUSADO_PERSISTENCIA";o.waitingSince=now;o.reason="Etapa pausada; conserva su reserva";}
                if(persistenceError.isEmpty())try{persist();}catch(RuntimeException ex){System.err.println(ex.getMessage());}
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
        long waiting=waitingNanos(o,now);
        List<Map<String,Object>> lines=o.lines.stream().map(l->Map.<String,Object>of("productId",l.productId,"name",product(l.productId).name,"type",product(l.productId).type,"units",l.units)).toList();
        Map<String,Object> s=new LinkedHashMap<>();
        s.put("id",o.id);s.put("clientId",o.clientId);s.put("clientName",o.clientId==0?"Pedido de version anterior":o.clientName);s.put("clientEmail",o.clientEmail==null?"":o.clientEmail);s.put("level",o.level);s.put("units",o.units);s.put("lines",lines);
        s.put("state",o.state);s.put("stage",o.state.equals("COMPLETADO")?"FINALIZADO":o.stage().name);
        s.put("reason",o.reason);s.put("progress",Math.min(100,worked*100/estimated));
        s.put("remainingMs",Math.max(0,estimated-worked));s.put("waitMs",waiting/1_000_000);
        int effective=effectiveLevel(o,now);
        s.put("effectiveLevel",effective);s.put("agingPromotions",o.level-effective);
        s.put("nextPromotionMs",policy.equals("ENVEJECIMIENTO")&&isWaiting(o)&&effective>1?(agingSeconds*1_000_000_000L-waiting%(agingSeconds*1_000_000_000L))/1_000_000:0);
        s.put("slaRate",o.slaRate);s.put("virtualWaitMs",waiting/1_000_000*o.slaRate);s.put("slaLimitMs",Scheduling.limitMs(o.level));
        s.put("slaStatus",o.slaRate==0?"SIN_MEDICION":Scheduling.sla(waiting/1_000_000*o.slaRate,o.level,o.state.equals("COMPLETADO")));
        s.put("heldResources",o.held.stream().map(r->r.key).toList());
        return s;
    }
    public Map<String,Object> snapshot() {
        synchronized(gate) {
            long now=System.nanoTime();List<Order> done=orders.stream().filter(o->o.state.equals("COMPLETADO")).toList();
            Map<String,Object> s=new LinkedHashMap<>();
            s.put("version","0.7");s.put("persistence",store.mode());s.put("persistenceError",persistenceError);s.put("recovered",recovered);s.put("policy",policy);s.put("automatic",automatic);s.put("agingSeconds",agingSeconds);s.put("clockRate",clockRate);s.put("comparison",comparison);s.put("serviceMetrics",serviceMetrics(now));
            s.put("total",orders.size());s.put("completed",done.size());
            s.put("activeCount",orders.stream().filter(o->o.state.equals("PROCESANDO")).count());
            s.put("waitingCount",orders.stream().filter(o->List.of("EN_ESPERA","ESPERA_STOCK","BLOQUEADO","CONFLICTO_REINTENTO").contains(o.state)).count());
            s.put("averageWaitMs",done.stream().mapToLong(o->o.waitNanos/1_000_000).average().orElse(0));
            s.put("averageProcessMs",done.stream().mapToLong(o->o.processNanos/1_000_000).average().orElse(0));
            List<Map<String,Object>> resourceData=new ArrayList<>();
            for(Resource r:resources.values()) {
                long busy=r.busyNanos;List<Map<String,Object>> slots=new ArrayList<>();
                for(int i=0;i<r.owners.length;i++) {
                    if(r.owners[i]!=0)busy+=now-r.since[i];
                    slots.add(Map.of("slot",i+1,"owner",r.owners[i],"state",r.owners[i]==0?"DISPONIBLE":"EN_USO","ownerLabel",r.owners[i]==0?"Disponible":r.owners[i]>0?"Pedido #"+r.owners[i]:"Recepcion #"+(-r.owners[i])));
                }
                resourceData.add(Map.of("key",r.key,"name",r.name,"capacity",r.owners.length,"available",r.permits.availablePermits(),"slots",slots,"occupancyPercent",busy*100.0/(Math.max(1,now-born)*r.owners.length)));
            }
            s.put("resources",resourceData);
            s.put("conflict",conflictSnapshot());
            s.put("clients",clients.stream().map(this::clientSnapshot).toList());
            s.put("products",products.stream().map(v->Map.of("id",v.id,"name",v.name,"type",v.type)).toList());
            s.put("types",typeNeeds.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->Map.of("name",e.getKey(),"needs",e.getValue(),"description",description(e.getKey()))).toList());
            s.put("active",orders.stream().filter(o->o.state.equals("PROCESANDO")).map(o->orderSnapshot(o,now)).toList());
            s.put("waiting",orders.stream().filter(o->o.state.equals("EN_ESPERA")||o.state.equals("ESPERA_STOCK")||o.state.equals("PAUSADO_PERSISTENCIA")||o.state.equals("BLOQUEADO")||o.state.equals("CONFLICTO_REINTENTO")).sorted(comparator()).map(o->orderSnapshot(o,now)).toList());
            // Historial visible acotado; el total y los promedios conservan toda la corrida.
            s.put("history",orders.stream().filter(o->o.state.equals("COMPLETADO")||o.state.equals("INTERRUMPIDO")).sorted(Comparator.comparingLong((Order o)->o.id).reversed()).limit(100).map(o->orderSnapshot(o,now)).toList());
            s.put("warehouse",warehouse.snapshot(products));
            s.put("receipts",receipts.stream().map(this::receiptSnapshot).toList());
            s.put("channel",Map.of("capacity",8,"queued",channel.size(),"durableBacklog",receipts.stream().filter(r->!r.state.equals("REGISTRADA")).count()));
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


    /** Dos hilos adquieren los mismos semaforos en orden inverso.
     * Ningun acquire bloqueante se ejecuta mientras se retiene el monitor gate. */
    private void associate(Order o,Client c){if(c!=null){o.clientId=c.id;o.clientName=c.name;o.clientEmail=c.email;}}
    public void startConflictForClient(long clientId,int productId,int units){synchronized(gate){healthy();startConflictInternal(productId,units,client(clientId));}}
    void startConflict(int productId,int units){startConflictInternal(productId,units,null);}
    private void startConflictInternal(int productId,int units,Client client) {
        synchronized(gate) {
            healthy();product(productId);
            if(!List.of("DISPONIBLE","RESUELTO").contains(conflictState))throw new IllegalArgumentException("Ya existe un conflicto activo");
            if(units<1||units>300)throw new IllegalArgumentException("Unidades por pedido: 1 a 300");
            if(!warehouse.shortage(List.of(new Line(productId,units*2))).isEmpty())throw new IllegalArgumentException("Se necesita stock disponible para los dos pedidos");
            conflictLog.clear();conflictWinner=0;conflictReady=0;conflictState="PREPARANDO";
            conflictA=new Order(++nextId,client==null?3:client.level,List.of(new Line(productId,units)),stagesFor(List.of(new Line(productId,units))));
            conflictB=new Order(++nextId,client==null?3:client.level,List.of(new Line(productId,units)),stagesFor(List.of(new Line(productId,units))));
            for(Order o:List.of(conflictA,conflictB)){o.slaRate=clockRate;associate(o,client);o.reservation=warehouse.reserve(o.lines);o.state="BLOQUEADO";o.reason="Preparando adquisicion inversa";orders.add(o);}
            persist();
            CountDownLatch firstHeld=new CountDownLatch(2);
            conflictThreadA=new Thread(()->conflictRun(conflictA,"ESCANER","CONTROL_CONFLICTO",firstHeld),"conflicto-pedido-"+conflictA.id);
            conflictThreadB=new Thread(()->conflictRun(conflictB,"CONTROL_CONFLICTO","ESCANER",firstHeld),"conflicto-pedido-"+conflictB.id);
            conflictThreadA.start();conflictThreadB.start();
        }
    }
    private void conflictAcquire(Order o,Resource r) throws InterruptedException {
        r.permits.acquire();
        synchronized(gate){
            for(int i=0;i<r.owners.length;i++)if(r.owners[i]==0){r.owners[i]=o.id;r.since[i]=System.nanoTime();break;}
            List<Resource> held=new ArrayList<>(o.held);held.add(r);o.held=List.copyOf(held);
            conflictLog.add("Pedido #"+o.id+" adquiere "+r.name);
        }
    }
    private void conflictRun(Order o,String first,String second,CountDownLatch firstHeld) {
        boolean continued=false;
        try {
            conflictAcquire(o,resources.get(first));firstHeld.countDown();firstHeld.await();
            synchronized(gate){
                o.reason="Retiene "+resources.get(first).name+"; espera "+resources.get(second).name;
                if(++conflictReady==2&&conflictState.equals("PREPARANDO")){conflictState="INTERBLOQUEO";}
                persist();
            }
            conflictAcquire(o,resources.get(second));
            synchronized(gate){o.reason="Conflicto liberado; prepara regreso al flujo";persist();}
            Thread.sleep(Math.max(1,200/timeScale));continued=true;
        } catch(InterruptedException ex){Thread.currentThread().interrupt();}
        catch(RuntimeException ex){System.err.println("Conflicto pausado: "+ex.getMessage());}
        finally {
            synchronized(gate){
                release(o);
                if(continued){
                    conflictState="RESUELTO";conflictLog.add("Pedido #"+o.id+" continua; ambos pedidos regresan al flujo normal");
                    for(Order v:List.of(conflictA,conflictB)){v.state="EN_ESPERA";v.reason="Conflicto resuelto; conserva reserva";}
                }else{o.state=closed?"EN_ESPERA":"CONFLICTO_REINTENTO";o.reason="Devuelto a cola; espera liberacion del conflicto";conflictLog.add("Pedido #"+o.id+" libera sus recursos sin consumir stock");}
                if(!closed&&persistenceError.isEmpty())try{persist();}catch(RuntimeException ex){System.err.println(ex.getMessage());}
            }
        }
    }
    public void resolveConflict(long winner) {
        synchronized(gate){
            healthy();
            if(!conflictState.equals("INTERBLOQUEO"))throw new IllegalArgumentException("No hay un interbloqueo listo para resolver");
            if(winner!=conflictA.id&&winner!=conflictB.id)throw new IllegalArgumentException("Selecciona uno de los dos pedidos");
            conflictWinner=winner;conflictState="RESOLVIENDO";
            conflictLog.add("Usuario elige pedido #"+winner+"; se interrumpe al otro hilo");
            persist();(winner==conflictA.id?conflictThreadB:conflictThreadA).interrupt();
        }
    }
    private Map<String,Object> conflictSnapshot(){
        long now=System.nanoTime();
        return Map.of("state",conflictState,"winner",conflictWinner,"orders",conflictA==null?List.of():List.of(orderSnapshot(conflictA,now),orderSnapshot(conflictB,now)),"events",List.copyOf(conflictLog));
    }


    public void setClockRate(int rate){synchronized(gate){healthy();if(rate<1||rate>3600)throw new IllegalArgumentException("Escala permitida: 1 a 3600");clockRate=rate;persist();}}
    public void compare(long seed,int count){
        int rate,aging;synchronized(gate){healthy();rate=clockRate;aging=agingSeconds;}
        Map<String,Object> result=Comparison.run(seed,count,rate,aging);
        synchronized(gate){healthy();comparison=result;persist();}
    }
    List<Scheduling.Step> experimentSteps(List<Line> lines){int units=lines.stream().mapToInt(Line::units).sum();return stagesFor(lines).stream().map(s->new Scheduling.Step(s.name,s.msPerUnit*units,s.needs)).toList();}
    Map<String,Integer> experimentCapacities(){Map<String,Integer> result=new LinkedHashMap<>();resources.forEach((k,v)->{if(!k.equals("CONTROL_CONFLICTO"))result.put(k,v.owners.length);});return result;}
    private List<Map<String,Object>> serviceMetrics(long now){
        List<Map<String,Object>> result=new ArrayList<>();
        for(int level=1;level<=5;level++){
            final int l=level;var measured=orders.stream().filter(o->o.level==l&&o.slaRate>0).toList();
            var done=measured.stream().filter(o->o.state.equals("COMPLETADO")).toList();
            long missed=done.stream().filter(o->o.waitNanos/1_000_000*o.slaRate>Scheduling.limitMs(l)).count();
            result.add(Map.of("level",l,"limitMinutes",Scheduling.limitMs(l)/60000,"measured",measured.size(),"completed",done.size(),"met",done.size()-missed,"missed",missed,"overduePending",measured.stream().filter(o->!o.state.equals("COMPLETADO")&&waitingNanos(o,now)/1_000_000*o.slaRate>Scheduling.limitMs(l)).count(),"averageWaitMinutes",done.stream().mapToLong(o->o.waitNanos/1_000_000*o.slaRate).average().orElse(0)/60000,"unmeasured",orders.stream().filter(o->o.level==l&&o.slaRate==0).count()));
        }
        return result;
    }

    private void healthy(){
        if(closed)throw new IllegalStateException("Simulacion cerrada");
        if(!persistenceError.isEmpty())throw new IllegalStateException(persistenceError);
    }
    private Map<String,Object> receiptSnapshot(Receipt r){
        return Map.of("id",r.id,"productId",r.productId,"product",product(r.productId).name,"units",r.units,"state",r.state,"reason",r.reason,"lotId",r.lotId);
    }
    public Map<String,Object> receive(int productId,int units){
        synchronized(gate){
            healthy();product(productId);
            if(units<1||units>600)throw new IllegalArgumentException("Recepcion permitida: 1 a 600 unidades");
            Receipt r=new Receipt(++nextReceiptId,productId,units);receipts.add(r);persist();
            return receiptSnapshot(r);
        }
    }
    // Solo se usa para preparar datos de pruebas; no existe endpoint de stock directo.
    void seedStock(int productId,int units){synchronized(gate){product(productId);if(warehouse.receive(0,productId,units)==0)throw new IllegalArgumentException("Stock de prueba excede capacidad");persist();}}
    private Receipt receipt(long id){return receipts.stream().filter(r->r.id==id).findFirst().orElseThrow();}
    private void releaseScan(Receipt r){
        Resource scanner=resources.get("ESCANER");
        if(scanner.owners[0]==-r.id){scanner.busyNanos+=System.nanoTime()-scanner.since[0];scanner.owners[0]=0;scanner.since[0]=0;scanner.permits.release();}
    }
    /** Productor: escanea y emite mensajes; ante canal lleno mantiene respaldo durable. */
    private void receptionLoop(){
        try {
            while(!Thread.currentThread().isInterrupted()){
                Receipt scanning=null;
                synchronized(gate){
                    if(closed)return;
                    if(persistenceError.isEmpty()){
                        for(Receipt r:receipts)if(r.state.equals("ESPERANDO_CANAL")&&!queuedReceipts.contains(r.id)){
                            if(!channel.offer(new ReceiptEvent(r.id,r.productId,r.units))){r.reason="Canal lleno; recepcion conservada en PostgreSQL";break;}
                            queuedReceipts.add(r.id);r.state="EN_COLA_INVENTARIO";r.reason="Mensaje enviado a Inventario";persist();
                        }
                        Resource scanner=resources.get("ESCANER");
                        if(scanner.permits.availablePermits()>0){
                            scanning=receipts.stream().filter(r->r.state.equals("PENDIENTE_ESCANEO")).findFirst().orElse(null);
                            if(scanning!=null){scanner.permits.acquire();scanner.owners[0]=-scanning.id;scanner.since[0]=System.nanoTime();scanning.state="ESCANEANDO";scanning.reason="Recepcion utiliza escaner compartido";persist();}
                        }
                    }
                }
                if(scanning==null){Thread.sleep(30);continue;}
                boolean finished=false;
                try{Thread.sleep(Math.max(1,(100L+scanning.units*5L)/timeScale));finished=true;}
                finally{
                    synchronized(gate){
                        releaseScan(scanning);
                        if(finished&&!closed&&persistenceError.isEmpty()){scanning.scanned=true;scanning.state="ESPERANDO_CANAL";scanning.reason="Esperando enviar mensaje";persist();}
                        else{scanning.state="PENDIENTE_ESCANEO";scanning.reason="Escaneo pausado";}
                    }
                }
            }
        }catch(InterruptedException ex){Thread.currentThread().interrupt();}
        catch(RuntimeException ex){System.err.println("Recepcion pausada: "+ex.getMessage());}
        finally{synchronized(gate){for(Receipt r:receipts)releaseScan(r);}}
    }
    /** Consumidor independiente. Recepcion puede producir mensajes mas rapido que este hilo. */
    private void inventoryLoop(){
        try {
            while(!Thread.currentThread().isInterrupted()){
                boolean halted;
                synchronized(gate){if(closed)return;halted=!persistenceError.isEmpty();}
                if(halted){Thread.sleep(100);continue;}
                ReceiptEvent event=channel.poll(100,TimeUnit.MILLISECONDS);
                Receipt r=null;
                synchronized(gate){
                    if(closed)return;
                    if(!persistenceError.isEmpty())continue;
                    if(event!=null){queuedReceipts.remove(event.receiptId);r=receipt(event.receiptId);if(r.state.equals("REGISTRADA"))continue;}
                    else r=receipts.stream().filter(v->v.state.equals("ESPERA_ESPACIO")&&v.units<=warehouse.free()).findFirst().orElse(null);
                    if(r!=null){r.state="INVENTARIO_PROCESANDO";r.reason="Inventario asigna ubicaciones";persist();}
                }
                if(r==null)continue;
                Thread.sleep(Math.max(1,1200/timeScale));
                synchronized(gate){
                    if(closed||!persistenceError.isEmpty())continue;
                    // Idempotencia: un mensaje repetido no puede crear un segundo lote.
                    if(r.lotId!=0)continue;
                    long lot=warehouse.receive(r.id,r.productId,r.units);
                    if(lot==0){r.state="ESPERA_ESPACIO";r.reason="Faltan "+(r.units-warehouse.free())+" espacios; se reintenta automaticamente";}
                    else{r.lotId=lot;r.state="REGISTRADA";r.reason="Lote #"+lot+" disponible en Inventario";}
                    persist();
                }
            }
        }catch(InterruptedException ex){Thread.currentThread().interrupt();}
        catch(RuntimeException ex){System.err.println("Inventario pausado: "+ex.getMessage());}
    }
    /** Checkpoint de todo el dominio: reserva y consumo nunca se confirman por separado. */
    private void persist(){
        if(!persistenceError.isEmpty())return;
        try {
            long now=System.nanoTime();
            for(Order o:orders)o.pendingWaitNanos=isWaiting(o)?Math.max(0,now-o.waitingSince):0;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(new DurableState(3,orders,receipts,warehouse,nextId,nextReceiptId,policy,clients,nextClientId,agingSeconds,clockRate,comparison));}
            Map<String,Object> projection=new LinkedHashMap<>(snapshot());
            projection.put("orders",orders.stream().map(o->orderSnapshot(o,System.nanoTime())).toList());
            store.save(bytes.toByteArray(),Json.encode(projection));lastWaitCheckpoint=now;
        }catch(Exception ex){persistenceError="Persistencia detenida. Reinicia backend cuando PostgreSQL este disponible.";automatic=false;throw new IllegalStateException(persistenceError,ex);}
    }
    private void restore(){
        byte[] bytes=store.load();if(bytes==null)return;
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes))){
            DurableState state=(DurableState)in.readObject();if(state.format!=3)throw new IOException("Formato incompatible");
            orders.addAll(state.orders);receipts.addAll(state.receipts);warehouse=state.warehouse;
            nextId=state.nextId;nextReceiptId=state.nextReceiptId;policy=state.policy;recovered=true;
            if(state.clients!=null)clients.addAll(state.clients);nextClientId=state.nextClientId;
            agingSeconds=state.agingSeconds==0?30:state.agingSeconds;
            clockRate=state.clockRate==0?60:state.clockRate;comparison=state.comparison==null?Map.of():state.comparison;
            for(Order o:orders){
                o.waitNanos+=o.pendingWaitNanos;o.pendingWaitNanos=0;
                o.held=List.of();o.stageStarted=0;o.waitingSince=System.nanoTime();
                if(!o.state.equals("COMPLETADO")){o.state=o.reservation==null?"ESPERA_STOCK":"EN_ESPERA";o.reason="Recuperado desde PostgreSQL";}
            }
            for(Receipt r:receipts)if(!List.of("REGISTRADA","ESPERA_ESPACIO").contains(r.state)){r.state=r.scanned?"ESPERANDO_CANAL":"PENDIENTE_ESCANEO";r.reason="Recepcion recuperada";}
        }catch(Exception ex){throw new IllegalStateException("Checkpoint incompatible o corrupto; no se modificaron datos",ex);}
    }
    @Override public void close() {
        synchronized(gate){closed=true;automatic=false;if(conflictThreadA!=null)conflictThreadA.interrupt();if(conflictThreadB!=null)conflictThreadB.interrupt();}
        try{if(conflictThreadA!=null)conflictThreadA.join(5000);if(conflictThreadB!=null)conflictThreadB.join(5000);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
        timers.shutdownNow();workers.shutdownNow();areas.shutdownNow();
        try{workers.awaitTermination(5,TimeUnit.SECONDS);areas.awaitTermination(5,TimeUnit.SECONDS);}
        catch(InterruptedException ex){Thread.currentThread().interrupt();}
        synchronized(gate){if(persistenceError.isEmpty())try{persist();}catch(RuntimeException ex){System.err.println(ex.getMessage());}store.close();}
    }
}
