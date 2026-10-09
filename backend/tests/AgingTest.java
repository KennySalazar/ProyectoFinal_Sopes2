import java.util.*;
import java.util.function.BooleanSupplier;

/** Pruebas de orden real de despacho y recuperacion del tiempo de espera. */
public final class AgingTest {
    static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
    static long n(Object v){return ((Number)v).longValue();}
    static void await(BooleanSupplier c)throws Exception{long end=System.currentTimeMillis()+10000;while(!c.getAsBoolean()){if(System.currentTimeMillis()>end)throw new AssertionError("Tiempo agotado");Thread.sleep(10);}}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Simulation s,String key){return (List<Map<String,Object>>)s.snapshot().get(key);}
    public static void main(String[] args)throws Exception{
        try(Simulation s=new Simulation(100)){
            s.setPolicy("ENVEJECIMIENTO");s.setAgingSeconds(1);
            for(int value:new int[]{0,3601}){try{s.setAgingSeconds(value);throw new AssertionError("Intervalo invalido aceptado");}catch(IllegalArgumentException expected){}}
            long old=n(s.create(5,List.of(new Simulation.Line(1,1))).get("id"));
            await(()->n(list(s,"waiting").get(0).get("effectiveLevel"))==1);
            long recent=n(s.create(1,List.of(new Simulation.Line(1,1))).get("id"));
            s.setPolicy("PRIORIDAD");check(n(list(s,"waiting").get(0).get("id"))==recent,"Prioridad estricta alterada");
            s.setPolicy("ENVEJECIMIENTO");check(n(list(s,"waiting").get(0).get("id"))==old,"Pedido viejo no asciende");
            check(n(list(s,"waiting").get(0).get("level"))==5,"Servicio contratado alterado");
            s.seedStock(1,1);s.start();await(()->n(s.snapshot().get("completed"))==1);
            check(n(list(s,"history").get(0).get("id"))==old,"Despacho no respeta envejecimiento");
            check(n(list(s,"waiting").get(0).get("id"))==recent,"Reserva duplicada");
            s.seedStock(1,1);await(()->n(s.snapshot().get("completed"))==2);
        }
        StateStore store=StateStore.memory();long saved;
        try(Simulation s=new Simulation(100,store)){
            s.setPolicy("ENVEJECIMIENTO");s.setAgingSeconds(1);s.create(5,List.of(new Simulation.Line(1,1)));
            await(()->n(list(s,"waiting").get(0).get("effectiveLevel"))==4);saved=n(list(s,"waiting").get(0).get("waitMs"));
        }
        try(Simulation s=new Simulation(100,store)){
            check(s.snapshot().get("policy").equals("ENVEJECIMIENTO")&&n(s.snapshot().get("agingSeconds"))==1,"Configuracion perdida");
            check(n(list(s,"waiting").get(0).get("waitMs"))>=saved&&n(list(s,"waiting").get(0).get("effectiveLevel"))<=4,"Espera perdida al reiniciar");
            s.setAgingSeconds(30);check(n(list(s,"waiting").get(0).get("effectiveLevel"))==5,"Intervalo no reevalua");
        }
        try(Simulation s=new Simulation()){
            s.setPolicy("ENVEJECIMIENTO");s.setAgingSeconds(1);s.seedStock(1,10);s.create(5,List.of(new Simulation.Line(1,10)));s.tick();
            Thread.sleep(1100);check(!list(s,"active").isEmpty()&&n(list(s,"active").get(0).get("effectiveLevel"))==5,"Procesamiento contado como espera");
        }
        System.out.println("AgingTest OK: ascenso, limite 1, desempate FIFO, despacho real, servicio intacto, reinicio e intervalo");
    }
}
