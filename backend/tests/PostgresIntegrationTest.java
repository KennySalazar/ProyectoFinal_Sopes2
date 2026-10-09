import java.nio.file.Path;
import java.util.*;

/** Ejecutar solamente contra una base de pruebas nueva, definida explicitamente. */
public final class PostgresIntegrationTest {
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    static long n(Object v){return ((Number)v).longValue();}
    @SuppressWarnings("unchecked") static Map<String,Object> warehouse(Simulation s){return (Map<String,Object>)s.snapshot().get("warehouse");}
    static void until(java.util.function.BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+20_000_000_000L;while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(10);}throw new AssertionError("Timeout");
    }
    static PostgresStore open(String url)throws Exception{return new PostgresStore(url,System.getenv().getOrDefault("DATABASE_USER","redxela"),System.getenv().getOrDefault("DATABASE_PASSWORD","redxela_local"),Path.of("db/V1__schema.sql"));}
    public static void main(String[] args)throws Exception{
        String url=System.getenv("TEST_DATABASE_URL");if(url==null)throw new IllegalArgumentException("Define TEST_DATABASE_URL con una base nueva de pruebas");
        PostgresStore store=open(url);if(store.load()!=null){store.close();throw new IllegalStateException("La base ya contiene una corrida; usa otra base de pruebas vacia");}
        try(var s=new Simulation(20,store)){
            s.setPolicy("ENVEJECIMIENTO");s.setAgingSeconds(5);s.setClockRate(600);
            long client=n(s.registerClient("Mario Yancor","mario@example.com",2).get("id"));
            s.createForClient(client,List.of(new Simulation.Line(2,25)));
            s.changeClientService(client,5);s.start();
            until(()->n(s.snapshot().get("waitingCount"))==1);check(n(s.snapshot().get("activeCount"))==0,"Procesa sin stock");
            s.receive(2,40);until(()->n(s.snapshot().get("completed"))==1);
            check(n(warehouse(s).get("occupied"))==15,"Consumo no exacto");s.compare(42,20);
        }
        try(var resumed=new Simulation(20,open(url))){
            check(Boolean.TRUE.equals(resumed.snapshot().get("recovered")),"No recupero PostgreSQL");
            check(resumed.snapshot().get("policy").equals("ENVEJECIMIENTO")&&n(resumed.snapshot().get("agingSeconds"))==5,"Configuracion perdida");
            check(n(resumed.snapshot().get("completed"))==1&&n(warehouse(resumed).get("occupied"))==15,"Datos incorrectos despues de reinicio");
            check(n(resumed.snapshot().get("clockRate"))==600&&((List<?>)((Map<?,?>)resumed.snapshot().get("comparison")).get("results")).size()==4,"Reloj o comparacion perdidos");
            var clients=(List<?>)resumed.snapshot().get("clients");
            check(clients.size()==1&&n(((Map<?,?>)clients.get(0)).get("level"))==5,"Cliente no recuperado");
            var history=(List<?>)resumed.snapshot().get("history");check(n(((Map<?,?>)history.get(0)).get("level"))==2,"Servicio historico cambiado");
            check(n(resumed.createForClient(1,List.of(new Simulation.Line(2,1))).get("id"))==2,"ID duplicado");
            check(n(resumed.registerClient("Kenny Salazar","kenny@example.com",1).get("id"))==2,"ID cliente duplicado");
        }
        System.out.println("PASS JDBC: recepcion, espera, consumo exacto, checkpoint PostgreSQL, reinicio, clientes, servicio historico, reloj, comparacion e IDs");
    }
}
