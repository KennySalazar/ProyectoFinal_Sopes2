import java.util.*;

/** Experimento determinista: eventos de llegada/finalizacion, recursos paralelos y etapas reales.
 * No ejecuta hilos ni modifica el inventario operativo. */
final class Comparison {
    private record Input(long id,int level,int product,int units,long arrival,List<Scheduling.Step> steps) { }
    private static final class Job {
        final Input input;
        int stage;
        long waitingSince,waitMs,finish,completed;
        boolean running,done;
        Job(Input input){this.input=input;waitingSince=input.arrival;}
    }
    static Map<String,Object> run(long seed,int count,int rate,int agingSeconds){
        if(count<10||count>100)throw new IllegalArgumentException("Cantidad permitida: 10 a 100 pedidos");
        if(rate<1||rate>3600||agingSeconds<1||agingSeconds>3600)throw new IllegalArgumentException("Escala e intervalo permitidos: 1 a 3600");
        Random random=new Random(seed);List<Input> inputs=new ArrayList<>();
        Map<String,Integer> capacities;try(Simulation blueprint=new Simulation()){
            capacities=blueprint.experimentCapacities();
            for(int i=0;i<count;i++){
                int level=i%5+1,product=random.nextInt(10)+1,units=random.nextInt(6)+1;
                inputs.add(new Input(i+1,level,product,units,i*100L,blueprint.experimentSteps(List.of(new Simulation.Line(product,units)))));
            }
        }
        List<Map<String,Object>> workload=inputs.stream().map(i->Map.<String,Object>of("id",i.id,"level",i.level,"productId",i.product,"units",i.units,"arrivalMs",i.arrival,"processMs",i.steps.stream().mapToLong(Scheduling.Step::durationMs).sum())).toList();
        List<Map<String,Object>> results=new ArrayList<>();for(String policy:Scheduling.POLICIES)results.add(simulate(inputs,capacities,policy,rate,agingSeconds));
        return Map.of("seed",seed,"count",count,"rate",rate,"agingSeconds",agingSeconds,"model","EVENTOS_DISCRETOS","arrivalEveryMs",100,"stockAssumption","Stock suficiente para toda la carga desde el inicio","workload",workload,"results",results);
    }
    private static Map<String,Object> simulate(List<Input> inputs,Map<String,Integer> capacity,String policy,int rate,int agingSeconds){
        List<Job> jobs=inputs.stream().map(Job::new).toList();Map<String,Integer> available=new LinkedHashMap<>(capacity);
        Map<String,Long> busy=new LinkedHashMap<>();capacity.keySet().forEach(k->busy.put(k,0L));
        long now=0;int completed=0;
        while(completed<jobs.size()){
            for(Job j:jobs)if(j.running&&j.finish<=now){
                var step=j.input.steps.get(j.stage);for(String resource:step.needs()){available.merge(resource,1,Integer::sum);busy.merge(resource,(long)step.durationMs(),Long::sum);}
                j.running=false;j.stage++;
                if(j.stage==j.input.steps.size()){j.done=true;j.completed=now;completed++;}else j.waitingSince=now;
            }
            final long time=now;
            List<Job> waiting=jobs.stream().filter(j->!j.done&&!j.running&&j.input.arrival<=time).sorted(Comparator.comparingLong((Job j)->Scheduling.rank(policy,j.input.id,j.input.level,j.input.units,j.waitMs+time-j.waitingSince,agingSeconds)).thenComparingLong(j->j.input.id)).toList();
            for(Job j:waiting){
                var step=j.input.steps.get(j.stage);
                if(step.needs().stream().anyMatch(k->available.get(k)==0))continue;
                for(String resource:step.needs())available.merge(resource,-1,Integer::sum);
                j.waitMs+=now-j.waitingSince;j.running=true;j.finish=now+step.durationMs();
            }
            if(completed==jobs.size())break;
            long next=Long.MAX_VALUE;
            for(Job j:jobs){if(j.running)next=Math.min(next,j.finish);else if(j.input.arrival>now)next=Math.min(next,j.input.arrival);}
            if(next==Long.MAX_VALUE)throw new IllegalStateException("Experimento sin eventos futuros");now=next;
        }
        final long duration=now;
        if(!available.equals(capacity))throw new IllegalStateException("Recursos no liberados");
        List<Map<String,Object>> service=new ArrayList<>();
        for(int level=1;level<=5;level++){
            final int l=level;var group=jobs.stream().filter(j->j.input.level==l).toList();
            long missed=group.stream().filter(j->j.waitMs*rate>Scheduling.limitMs(l)).count();
            service.add(Map.of("level",level,"count",group.size(),"met",group.size()-missed,"missed",missed,"averageWaitMinutes",group.stream().mapToLong(j->j.waitMs*rate).average().orElse(0)/60000,"maxWaitMinutes",group.stream().mapToLong(j->j.waitMs*rate).max().orElse(0)/60000.0));
        }
        List<Map<String,Object>> trace=jobs.stream().map(j->Map.<String,Object>of("id",j.input.id,"level",j.input.level,"units",j.input.units,"waitMs",j.waitMs,"waitMinutes",j.waitMs*rate/60000.0,"completedMs",j.completed,"sla",Scheduling.sla(j.waitMs*rate,j.input.level,true))).toList();
        List<Map<String,Object>> occupancy=capacity.entrySet().stream().map(e->Map.<String,Object>of("resource",e.getKey(),"percent",busy.get(e.getKey())*100.0/(duration*e.getValue()))).toList();
        Map<String,Object> result=new LinkedHashMap<>();result.put("policy",policy);result.put("completed",jobs.size());result.put("durationMinutes",now*rate/60000.0);result.put("averageWaitMinutes",jobs.stream().mapToLong(j->j.waitMs*rate).average().orElse(0)/60000);result.put("maxWaitMinutes",jobs.stream().mapToLong(j->j.waitMs*rate).max().orElse(0)/60000.0);result.put("missed",jobs.stream().filter(j->j.waitMs*rate>Scheduling.limitMs(j.input.level)).count());result.put("services",service);result.put("orders",trace);result.put("occupancy",occupancy);return result;
    }
}
