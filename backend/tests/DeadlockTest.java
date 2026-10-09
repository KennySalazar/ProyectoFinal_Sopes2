import java.util.*;
import java.util.function.BooleanSupplier;

/** Verifica bloqueo real, ambas elecciones, liberacion y recuperacion. */
public final class DeadlockTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void await(BooleanSupplier condition)throws Exception{long end=System.currentTimeMillis()+10000;while(!condition.getAsBoolean()){if(System.currentTimeMillis()>end)throw new AssertionError("Tiempo agotado");Thread.sleep(10);}}
    @SuppressWarnings("unchecked") static Map<String,Object> conflict(Simulation s){return (Map<String,Object>)s.snapshot().get("conflict");}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> orders(Simulation s){return (List<Map<String,Object>>)conflict(s).get("orders");}
    @SuppressWarnings("unchecked") static void resourcesFree(Simulation s){for(var r:(List<Map<String,Object>>)s.snapshot().get("resources"))check(r.get("available").equals(r.get("capacity")),"Permiso filtrado: "+r.get("key"));}
    public static void main(String[] args)throws Exception{
        for(int choice=0;choice<2;choice++){
            try(Simulation s=new Simulation(100)){
                s.seedStock(1,20);s.start();s.startConflict(1,5);
                await(()->conflict(s).get("state").equals("INTERBLOQUEO"));
                check(orders(s).stream().allMatch(o->((List<?>)o.get("heldResources")).size()==1),"Cada hilo retiene un recurso");
                Thread.sleep(150);check(conflict(s).get("state").equals("INTERBLOQUEO"),"Se resolvio solo");
                try{s.startConflict(1,1);throw new AssertionError("Conflicto doble aceptado");}catch(IllegalArgumentException expected){}
                try{s.resolveConflict(999);throw new AssertionError("Ganador invalido aceptado");}catch(IllegalArgumentException expected){}
                s.resolveConflict(((Number)orders(s).get(choice).get("id")).longValue());
                await(()->((Number)s.snapshot().get("completed")).intValue()==2);
                check(conflict(s).get("state").equals("RESUELTO"),"Estado sin resolver");resourcesFree(s);
                s.startConflict(1,5);await(()->conflict(s).get("state").equals("INTERBLOQUEO"));
                s.resolveConflict(((Number)orders(s).get(0).get("id")).longValue());await(()->((Number)s.snapshot().get("completed")).intValue()==4);resourcesFree(s);
            }
        }
        StateStore store=StateStore.memory();
        Simulation first=new Simulation(100,store);first.seedStock(1,10);first.start();first.startConflict(1,5);await(()->conflict(first).get("state").equals("INTERBLOQUEO"));first.close();resourcesFree(first);
        try(Simulation restored=new Simulation(100,store)){check(conflict(restored).get("state").equals("DISPONIBLE"),"Conflicto runtime restaurado");restored.start();await(()->((Number)restored.snapshot().get("completed")).intValue()==2);resourcesFree(restored);}
        try(Simulation empty=new Simulation(100)){try{empty.startConflict(1,1);throw new AssertionError("Stock insuficiente aceptado");}catch(IllegalArgumentException expected){}check(((Number)empty.snapshot().get("total")).intValue()==0,"Pedido parcial creado");}
        System.out.println("DeadlockTest OK: ambas elecciones, repeticion, cierre, recuperacion y stock");
    }
}
