import java.util.*;

/** Pruebas de invariantes: propietarios, capacidades, colas y liberacion. */
public final class SimulationTest {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> list(Map<String,Object> s,String key){return (List<Map<String,Object>>)s.get(key);}
    private static void invalid(Runnable action){try{action.run();throw new AssertionError("Se acepto entrada invalida");}catch(IllegalArgumentException expected){}}
    private static long number(Object value){return ((Number)value).longValue();}
    private static void invariant(Map<String,Object> s) {
        Map<Long,Map<String,Object>> active=new HashMap<>();
        for(var o:list(s,"active"))active.put(number(o.get("id")),o);
        for(var r:list(s,"resources")){
            var slots=list(r,"slots");long used=slots.stream().filter(slot->number(slot.get("owner"))>0).count();
            check(used+number(r.get("available"))==number(r.get("capacity")),"Capacidad/propietarios inconsistentes");
            Set<Long> owners=new HashSet<>();
            for(var slot:slots){long owner=number(slot.get("owner"));if(owner>0){
                check(owners.add(owner),"Pedido duplicado en recurso");
                check(active.containsKey(owner),"Propietario no activo");
                check(((List<?>)active.get(owner).get("heldResources")).contains(r.get("key")),"Recurso no registrado en pedido");
            }}
            double occupancy=((Number)r.get("occupancyPercent")).doubleValue();
            check(occupancy>=0&&occupancy<=100.001,"Ocupacion fuera de rango");
        }
    }
    public static void main(String[] args) throws Exception {
        try(var s=new Simulation(20)){
            for(int product=1;product<=5;product++)s.seedStock(product,60);
            invalid(()->s.create(0,List.of(new Simulation.Line(1,1))));
            invalid(()->s.create(3,List.of(new Simulation.Line(100,1))));
            invalid(()->s.create(3,List.of(new Simulation.Line(1,0))));
            invalid(()->s.create(3,List.of(new Simulation.Line(1,600),new Simulation.Line(2,1))));
            invalid(()->s.setPolicy("INVALIDA"));
            var mixed=s.create(5,List.of(new Simulation.Line(1,2),new Simulation.Line(1,3),new Simulation.Line(2,1)));
            check(number(mixed.get("units"))==6,"Total incorrecto");check(list(mixed,"lines").size()==2,"No consolido productos");
            s.create(1,List.of(new Simulation.Line(2,10)));s.create(3,List.of(new Simulation.Line(3,1)));
            check(number(list(s.snapshot(),"waiting").get(0).get("level"))==1,"Prioridad incorrecta");
            s.setPolicy("FIFO");check(number(list(s.snapshot(),"waiting").get(0).get("id"))==1,"FIFO incorrecto");
            s.setPolicy("UNIDADES");check(number(list(s.snapshot(),"waiting").get(0).get("units"))==1,"Menor trabajo incorrecto");
            s.setPolicy("PRIORIDAD");
            for(int i=0;i<25;i++)s.create(i%5+1,List.of(new Simulation.Line(i%5+1,4)));
            s.create(2,List.of(new Simulation.Line(1,1),new Simulation.Line(2,1),new Simulation.Line(3,1),new Simulation.Line(4,1),new Simulation.Line(5,1)));
            s.start();long deadline=System.nanoTime()+15_000_000_000L;boolean blocked=false;Set<String> stages=new HashSet<>();
            while(System.nanoTime()<deadline){
                var state=s.snapshot();invariant(state);
                for(var o:list(state,"active"))stages.add(o.get("stage").toString());
                blocked|=list(state,"waiting").stream().anyMatch(o->o.get("reason").toString().startsWith("Sin disponibilidad:"));
                if(number(state.get("completed"))==29)break;
                Thread.sleep(5);
            }
            var finalState=s.snapshot();check(number(finalState.get("completed"))==29,"Pedidos sin completar");
            check(blocked,"No se demostro contencion");
            check(stages.containsAll(List.of("PREPARACION","EMPAQUE","CALIDAD","DESPACHO_ESCANEO","DESPACHO_CARGA")),"Faltan etapas");
            for(var r:list(finalState,"resources"))check(number(r.get("available"))==number(r.get("capacity")),"Permisos no liberados");
            for(var o:list(finalState,"history"))check(number(o.get("progress"))==100&&number(o.get("remainingMs"))==0,"Finalizacion incorrecta");
            System.out.println("PASS: validacion, lineas mixtas, 3 politicas, 29 pedidos, 5 etapas, capacidades, propietarios y liberacion");
        }
        try(var parallelLoad=new Simulation(5)) {
            parallelLoad.seedStock(1,120);
            for(int i=0;i<6;i++)parallelLoad.create(3,List.of(new Simulation.Line(1,20)));
            parallelLoad.start();long loadDeadline=System.nanoTime()+10_000_000_000L;boolean twoLoads=false;
            while(System.nanoTime()<loadDeadline) {
                var state=parallelLoad.snapshot();invariant(state);
                for(var resource:list(state,"resources"))if(resource.get("key").equals("CARGA"))
                    twoLoads|=number(resource.get("available"))==0;
                if(number(state.get("completed"))==6)break;
                Thread.sleep(5);
            }
            check(twoLoads,"No se utilizaron ambas areas de carga");
            check(number(parallelLoad.snapshot().get("completed"))==6,"No termino la prueba de carga paralela");
            System.out.println("PASS: ambas areas de carga en paralelo con escaner unico");
        }
        Simulation interrupted=new Simulation();interrupted.seedStock(4,100);interrupted.create(1,List.of(new Simulation.Line(4,100)));interrupted.start();
        long deadline=System.nanoTime()+2_000_000_000L;
        while(number(interrupted.snapshot().get("activeCount"))==0&&System.nanoTime()<deadline)Thread.sleep(5);
        check(number(interrupted.snapshot().get("activeCount"))>0,"No inicio prueba de interrupcion");
        interrupted.close();var closed=interrupted.snapshot();invariant(closed);
        for(var r:list(closed,"resources"))check(number(r.get("available"))==number(r.get("capacity")),"Fuga al cerrar");
        System.out.println("PASS: cierre cooperativo e interrupcion sin fuga de permisos");
        check(Json.encode(Map.of("x","a\"b\n")).contains("a\\\"b\\n"),"Escape JSON incorrecto");
    }
}
