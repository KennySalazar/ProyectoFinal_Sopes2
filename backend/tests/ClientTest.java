import java.util.*;
import java.util.function.BooleanSupplier;

/** Clientes persistentes y servicio historico inmutable por pedido. */
public final class ClientTest {
    static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
    static void invalid(Runnable r){try{r.run();throw new AssertionError("Entrada invalida aceptada");}catch(IllegalArgumentException expected){}}
    static void await(BooleanSupplier c)throws Exception{long end=System.currentTimeMillis()+10000;while(!c.getAsBoolean()){if(System.currentTimeMillis()>end)throw new AssertionError("Tiempo agotado");Thread.sleep(10);}}
    static long n(Object v){return ((Number)v).longValue();}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Simulation s,String key){return (List<Map<String,Object>>)s.snapshot().get(key);}
    public static void main(String[] args)throws Exception{
        StateStore store=StateStore.memory();long client;
        try(Simulation s=new Simulation(100,store)){
            invalid(()->s.setAutomatic(true));invalid(()->s.registerClient("A","a@example.com",1));invalid(()->s.registerClient("Mario","correo",1));invalid(()->s.registerClient("Mario","a@example.com",0));
            client=n(s.registerClient("  Mario Yancor  "," MARIO@EXAMPLE.COM ",2).get("id"));
            check(list(s,"clients").get(0).get("email").equals("mario@example.com"),"Normalizacion");
            invalid(()->s.registerClient("Otro","mario@example.com",3));invalid(()->s.createForClient(999,List.of(new Simulation.Line(1,1))));
            s.seedStock(1,20);
            var old=s.createForClient(client,List.of(new Simulation.Line(1,5)));check(n(old.get("level"))==2&&n(old.get("clientId"))==client,"Asociacion");
            s.changeClientService(client,5);
            var recent=s.createForClient(client,List.of(new Simulation.Line(1,5)));check(n(recent.get("level"))==5,"Servicio nuevo");
            check(n(old.get("level"))==2,"Servicio historico modificado");
            s.start();await(()->n(s.snapshot().get("completed"))==2);
            check(list(s,"history").stream().anyMatch(o->n(o.get("id"))==n(old.get("id"))&&n(o.get("level"))==2),"Historial incorrecto");
            s.startConflictForClient(client,1,5);
            await(()->((Map<?,?>)s.snapshot().get("conflict")).get("state").equals("INTERBLOQUEO"));
            var conflict=(Map<?,?>)s.snapshot().get("conflict");var orders=(List<?>)conflict.get("orders");
            check(orders.stream().allMatch(o->n(((Map<?,?>)o).get("clientId"))==client&&n(((Map<?,?>)o).get("level"))==5),"Cliente del conflicto");
            s.resolveConflict(n(((Map<?,?>)orders.get(0)).get("id")));await(()->n(s.snapshot().get("completed"))==4);
            check(n(list(s,"clients").get(0).get("completed"))==4,"Conteo cliente");
        }
        try(Simulation s=new Simulation(100,store)){
            check(list(s,"clients").size()==1&&n(list(s,"clients").get(0).get("level"))==5,"Cliente perdido al recuperar");
            check(n(s.registerClient("Kenny Salazar","kenny@example.com",1).get("id"))==2,"ID cliente repetido");
            s.setAutomatic(true);s.start();await(()->n(s.snapshot().get("total"))>4);s.setAutomatic(false);
            check(list(s,"waiting").stream().allMatch(o->n(o.get("clientId"))>0),"Generador sin cliente");
        }
        System.out.println("ClientTest OK: validacion, correo unico, cliente, servicio historico, conflicto, generador y recuperacion");
    }
}
