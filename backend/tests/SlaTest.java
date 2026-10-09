import java.util.*;
import java.util.function.BooleanSupplier;

public final class SlaTest {
    static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
    static long n(Object o){return ((Number)o).longValue();}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Simulation s,String key){return (List<Map<String,Object>>)s.snapshot().get(key);}
    static void await(BooleanSupplier c)throws Exception{long end=System.currentTimeMillis()+10000;while(!c.getAsBoolean()){if(System.currentTimeMillis()>end)throw new AssertionError("Tiempo agotado");Thread.sleep(10);}}
    public static void main(String[] args)throws Exception{
        long[] limits={60000,600000,1800000,3600000,7200000};
        for(int i=0;i<5;i++){check(Scheduling.limitMs(i+1)==limits[i],"Limite incorrecto");check(Scheduling.sla(limits[i],i+1,true).equals("CUMPLIDO"),"Igualdad no inclusiva");check(Scheduling.sla(limits[i]+1,i+1,true).equals("INCUMPLIDO"),"Incumplimiento omitido");}
        StateStore store=StateStore.memory();
        try(Simulation s=new Simulation(100,store)){
            s.setClockRate(3600);s.registerClient("Mario","mario@example.com",2);s.createForClient(1,List.of(new Simulation.Line(1,1)));
            s.setClockRate(1);await(()->list(s,"waiting").get(0).get("slaStatus").equals("VENCIDO"));
            check(n(list(s,"waiting").get(0).get("slaRate"))==3600,"Escala historica modificada");
            s.seedStock(1,2);s.start();await(()->n(s.snapshot().get("completed"))==1);
            check(list(s,"history").get(0).get("slaStatus").equals("INCUMPLIDO"),"SLA no conservado");
            s.createForClient(1,List.of(new Simulation.Line(1,1)));await(()->n(s.snapshot().get("completed"))==2);
            check(list(s,"history").get(0).get("slaStatus").equals("CUMPLIDO"),"SLA del pedido nuevo incorrecto");
            var metrics=list(s,"serviceMetrics").get(1);check(n(metrics.get("met"))==1&&n(metrics.get("missed"))==1,"Metricas por servicio");
        }
        try(Simulation s=new Simulation(100,store)){check(n(s.snapshot().get("clockRate"))==1&&list(s,"history").get(1).get("slaStatus").equals("INCUMPLIDO"),"SLA perdido al recuperar");}
        System.out.println("SlaTest OK: limites, escala por pedido, vencimiento, cumplimiento, metricas y reinicio");
    }
}
