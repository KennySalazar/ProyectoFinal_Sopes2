import java.util.*;

/** Reglas compartidas por el motor con hilos y el experimento de eventos discretos. */
final class Scheduling {
    static final List<String> POLICIES=List.of("PRIORIDAD","FIFO","UNIDADES","ENVEJECIMIENTO");
    record Step(String name,int durationMs,List<String> needs) { }
    static int effectiveLevel(int level,long waitMs,int agingSeconds){return (int)Math.max(1,level-waitMs/(agingSeconds*1000L));}
    static long rank(String policy,long id,int level,int units,long waitMs,int agingSeconds){
        return switch(policy){case "FIFO"->id;case "UNIDADES"->units;case "ENVEJECIMIENTO"->effectiveLevel(level,waitMs,agingSeconds);default->level;};
    }
    static long limitMs(int level){return switch(level){case 1->60_000L;case 2->600_000L;case 3->1_800_000L;case 4->3_600_000L;default->7_200_000L;};}
    static String sla(long virtualWaitMs,int level,boolean complete){return virtualWaitMs>limitMs(level)?(complete?"INCUMPLIDO":"VENCIDO"):(complete?"CUMPLIDO":"EN_PLAZO");}
}
