import java.util.*;

/** Reproducibilidad, tiempos, capacidades y aislamiento del experimento. */
public final class ComparisonTest {
    static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
    static long n(Object o){return ((Number)o).longValue();}
    static double d(Object o){return ((Number)o).doubleValue();}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Map<String,Object> m,String key){return (List<Map<String,Object>>)m.get(key);}
    public static void main(String[] args){
        var first=Comparison.run(42,40,60,1);check(first.equals(Comparison.run(42,40,60,1)),"Resultado no reproducible");
        check(!first.get("workload").equals(Comparison.run(43,40,60,1).get("workload")),"Semilla ignorada");
        var doubleRate=Comparison.run(42,40,120,1);
        Map<Long,Map<String,Object>> input=new HashMap<>();list(first,"workload").forEach(o->input.put(n(o.get("id")),o));
        for(int index=0;index<4;index++){
            var r=list(first,"results").get(index);var doubled=list(doubleRate,"results").get(index);
            check(n(r.get("completed"))==40,"Pedidos perdidos");
            check(Math.abs(d(doubled.get("averageWaitMinutes"))-2*d(r.get("averageWaitMinutes")))<0.000001,"Reloj inconsistente");
            Set<Long> ids=new HashSet<>();long missed=0;
            for(var order:list(r,"orders")){
                long id=n(order.get("id"));check(ids.add(id),"ID duplicado");var source=input.get(id);
                check(n(order.get("completedMs"))-n(source.get("arrivalMs"))-n(source.get("processMs"))==n(order.get("waitMs")),"Espera no corresponde a llegada/proceso/finalizacion");
                check(n(order.get("waitMs"))>=0,"Espera negativa");if(order.get("sla").equals("INCUMPLIDO"))missed++;
            }
            check(missed==n(r.get("missed")),"Conteo SLA incorrecto");
            for(var occupancy:list(r,"occupancy"))check(d(occupancy.get("percent"))>=0&&d(occupancy.get("percent"))<=100.00001,"Capacidad excedida");
            check(list(r,"services").stream().mapToLong(o->n(o.get("count"))).sum()==40,"Servicio perdido");
        }
        StateStore store=StateStore.memory();
        try(Simulation s=new Simulation(100,store)){
            s.registerClient("Mario","mario@example.com",5);s.seedStock(1,10);s.createForClient(1,List.of(new Simulation.Line(1,5)));s.setAgingSeconds(1);
            s.compare(42,40);check(n(s.snapshot().get("total"))==1,"Comparacion creo pedidos operativos");
            check(n(((Map<?,?>)s.snapshot().get("warehouse")).get("occupied"))==10,"Comparacion altero stock");
            check(s.snapshot().get("policy").equals("PRIORIDAD"),"Comparacion cambio politica operativa");
        }
        try(Simulation s=new Simulation(100,store)){check(first.equals(s.snapshot().get("comparison")),"Resultado perdido al reiniciar");}
        try{Comparison.run(42,101,60,1);throw new AssertionError("Carga invalida aceptada");}catch(IllegalArgumentException expected){}
        System.out.println("ComparisonTest OK: misma carga, reproducibilidad, tiempos, SLA, capacidades, escala, aislamiento y recuperacion");
    }
}
