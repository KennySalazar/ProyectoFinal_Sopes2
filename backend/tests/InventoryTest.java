import java.util.*;

/** Pruebas de stock, almacenamiento, canal y recuperacion sin dependencias externas. */
public final class InventoryTest {
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    static long n(Object v){return ((Number)v).longValue();}
    @SuppressWarnings("unchecked") static Map<String,Object> warehouse(Simulation s){return (Map<String,Object>)s.snapshot().get("warehouse");}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Map<String,Object> s,String key){return (List<Map<String,Object>>)s.get(key);}
    static Map<String,Object> stock(Simulation s,int product){return rows(warehouse(s),"stocks").stream().filter(p->n(p.get("productId"))==product).findFirst().orElseThrow();}
    interface Done{boolean get();}
    static void until(Done f)throws Exception{long end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end){if(f.get())return;Thread.sleep(5);}throw new AssertionError("Timeout");}
    public static void main(String[] args)throws Exception{
        Warehouse w=new Warehouse();w.receive(1,1,25);check(w.allocations.size()==3,"No reparte lotes");
        check(w.allocations.get(0).cell==0&&w.allocations.get(1).cell==17&&w.allocations.get(2).cell==34,"No son ubicaciones no contiguas");
        var failed=w.reserve(List.of(new Simulation.Line(1,5),new Simulation.Line(2,5)));
        check(failed==null&&w.reserved(1)==0,"Reserva parcial");
        w.receive(2,2,5);var reservation=w.reserve(List.of(new Simulation.Line(1,5),new Simulation.Line(2,5)));
        check(reservation!=null&&w.reserved(1)==5&&w.reserved(2)==5,"Reserva mixta incorrecta");
        w.consume(reservation);check(w.stock(1)==20&&w.stock(2)==0,"Consumo incorrecto");
        check(w.receive(3,3,600)==0&&w.occupied()==20,"Excedio capacidad o ingreso parcial");
        System.out.println("PASS almacen: lotes no contiguos, reservas atomicas, consumo y capacidad");
        StateStore durable=StateStore.memory();
        try(var s=new Simulation(20,durable)){
            s.create(1,List.of(new Simulation.Line(1,5),new Simulation.Line(2,5)));s.start();
            until(()->rows(s.snapshot(),"waiting").get(0).get("state").equals("ESPERA_STOCK"));
            s.receive(1,5);until(()->n(stock(s,1).get("stock"))==5);
            check(n(stock(s,1).get("reserved"))==0,"Retiene stock parcial esperando otro producto");
            s.receive(2,5);until(()->n(s.snapshot().get("completed"))==1);
            check(n(stock(s,1).get("stock"))==0&&n(stock(s,2).get("stock"))==0,"No consumio al terminar");
        }
        try(var recovered=new Simulation(20,durable)){
            check(Boolean.TRUE.equals(recovered.snapshot().get("recovered")),"No recupero checkpoint");
            check(n(recovered.snapshot().get("completed"))==1&&n(warehouse(recovered).get("occupied"))==0,"Doble consumo al reiniciar");
            check(n(recovered.create(2,List.of(new Simulation.Line(1,1))).get("id"))==2,"ID repetido");
        }
        System.out.println("PASS pedidos: espera de stock, reactivacion, sin reserva parcial, checkpoint e IDs");
        try(var full=new Simulation(20)){
            full.seedStock(3,600);full.receive(1,40);full.start();
            until(()->rows(full.snapshot(),"receipts").get(0).get("state").equals("ESPERA_ESPACIO"));
            check(n(warehouse(full).get("occupied"))==600,"Almacen sobrepasado");
            full.create(1,List.of(new Simulation.Line(3,40)));
            until(()->rows(full.snapshot(),"receipts").get(0).get("state").equals("REGISTRADA"));
            check(n(warehouse(full).get("occupied"))==600&&n(stock(full,1).get("stock"))==40,"No reintento recepcion pendiente");
        }
        System.out.println("PASS capacidad: recepcion pendiente y reintento al despachar");
        StateStore messages=StateStore.memory();
        try(var producer=new Simulation(10,messages)){producer.seedStock(1,20);producer.create(1,List.of(new Simulation.Line(1,10)));for(int i=0;i<40;i++)producer.receive(1,1);}
        try(var resumed=new Simulation(10,messages)){
            resumed.start();final boolean[] full={false}, receiptScan={false}, orderScan={false};
            until(()->{
                @SuppressWarnings("unchecked") var channel=(Map<String,Object>)resumed.snapshot().get("channel");
                full[0]|=n(channel.get("queued"))==8;
                var snapshot=resumed.snapshot();
                for(var resource:rows(snapshot,"resources"))if(resource.get("key").equals("ESCANER")){
                    long owner=n(rows(resource,"slots").get(0).get("owner"));
                    receiptScan[0]|=owner<0;orderScan[0]|=owner>0;
                    check(n(resource.get("available"))==(owner==0?1:0),"Escaner compartido inconsistente");
                }
                return n(snapshot.get("completed"))==1&&rows(snapshot,"receipts").stream().allMatch(r->r.get("state").equals("REGISTRADA"));
            });
            check(full[0],"No se demostro canal lleno");check(receiptScan[0]&&orderScan[0],"No se compartio el escaner entre ambas areas");check(n(stock(resumed,1).get("stock"))==50,"Perdio o duplico mensajes");
            check(rows(warehouse(resumed),"lots").size()==41,"Lotes duplicados o faltantes");
        }
        System.out.println("PASS canal: 40 recepciones recuperadas, backpressure, escaner compartido, sin perdida ni duplicacion");
        class FailingStore implements StateStore {
            final StateStore backing=StateStore.memory();boolean fail;
            public byte[] load(){return backing.load();}
            public void save(byte[] bytes,String projection){if(fail)throw new IllegalStateException("Fallo simulado");backing.save(bytes,projection);}
            public String mode(){return "PRUEBA_FALLO";}
        }
        var fault=new FailingStore();
        try(var paused=new Simulation(20,fault)){
            paused.seedStock(1,10);paused.create(1,List.of(new Simulation.Line(1,5)));fault.fail=true;paused.start();
            until(()->!paused.snapshot().get("persistenceError").equals(""));
            check(n(paused.snapshot().get("completed"))==0,"Continua despues de fallar persistencia");
        }
        fault.fail=false;
        try(var recovery=new Simulation(20,fault)){
            recovery.start();until(()->n(recovery.snapshot().get("completed"))==1);
            check(n(stock(recovery,1).get("stock"))==5,"Recuperacion descontando stock doble");
        }
        System.out.println("PASS fallo: pausa de persistencia, recuperacion y descuento unico");

    }
}
