import java.io.Serializable;
import java.util.*;

/** Solo Inventario modifica ubicaciones y existencias; acceso bajo el monitor del motor. */
final class Warehouse implements Serializable {
    private static final long serialVersionUID=1L;
    static final int CELL_COUNT=60, CELL_CAPACITY=10;
    static final class Allocation implements Serializable {
        private static final long serialVersionUID=1L;
        final long id, lotId;
        final int cell, productId;
        int quantity, reserved;
        Allocation(long id,long lotId,int cell,int productId,int quantity){this.id=id;this.lotId=lotId;this.cell=cell;this.productId=productId;this.quantity=quantity;}
    }
    record Lot(long id,long receiptId,int productId,int receivedUnits,long createdAt) implements Serializable { }
    final List<Allocation> allocations=new ArrayList<>();
    final List<Lot> lots=new ArrayList<>();
    long nextAllocation, nextLot;
    int occupied(){return allocations.stream().mapToInt(a->a.quantity).sum();}
    int free(){return CELL_COUNT*CELL_CAPACITY-occupied();}
    int stock(int productId){return allocations.stream().filter(a->a.productId==productId).mapToInt(a->a.quantity).sum();}
    int available(int productId){return allocations.stream().filter(a->a.productId==productId).mapToInt(a->a.quantity-a.reserved).sum();}
    int reserved(int productId){return stock(productId)-available(productId);}
    /** La verificacion completa sucede antes de modificar una ubicacion. */
    long receive(long receiptId,int productId,int units){
        if(units>free())return 0;
        long lotId=++nextLot;lots.add(new Lot(lotId,receiptId,productId,units,System.currentTimeMillis()));
        int remaining=units;
        // Paso 17 coprimo con 60: recorre todas las celdas y reparte por pasillos/niveles.
        for(int position=0;position<CELL_COUNT&&remaining>0;position++){
            int cell=(position*17)%CELL_COUNT;
            int used=allocations.stream().filter(a->a.cell==cell).mapToInt(a->a.quantity).sum();
            int count=Math.min(remaining,CELL_CAPACITY-used);
            if(count>0){allocations.add(new Allocation(++nextAllocation,lotId,cell,productId,count));remaining-=count;}
        }
        if(remaining!=0)throw new IllegalStateException("Capacidad de almacen inconsistente");
        return lotId;
    }
    String shortage(List<Simulation.Line> lines){
        List<String> missing=new ArrayList<>();
        for(var l:lines)if(available(l.productId())<l.units())missing.add("Producto #"+l.productId()+": faltan "+(l.units()-available(l.productId())));
        return String.join("; ",missing);
    }
    Map<Long,Integer> reserve(List<Simulation.Line> lines){
        if(!shortage(lines).isEmpty())return null;
        Map<Long,Integer> reservation=new LinkedHashMap<>();
        for(var l:lines){
            int left=l.units();
            for(var a:allocations){
                if(a.productId!=l.productId())continue;
                int n=Math.min(left,a.quantity-a.reserved);
                if(n>0){a.reserved+=n;reservation.put(a.id,n);left-=n;}
                if(left==0)break;
            }
        }
        return reservation;
    }
    /** El despacho consume la reserva una sola vez junto con el estado COMPLETADO. */
    void consume(Map<Long,Integer> reservation){
        for(var e:reservation.entrySet()){
            var a=allocations.stream().filter(v->v.id==e.getKey()).findFirst().orElseThrow();
            if(a.reserved<e.getValue()||a.quantity<e.getValue())throw new IllegalStateException("Reserva inconsistente");
        }
        for(var e:reservation.entrySet()){
            var a=allocations.stream().filter(v->v.id==e.getKey()).findFirst().orElseThrow();a.reserved-=e.getValue();a.quantity-=e.getValue();
        }
        allocations.removeIf(a->a.quantity==0);
    }
    static String label(int cell){return ""+(char)('A'+cell/10)+"-N"+(cell%10/5+1)+"-U"+(cell%5+1);}
    Map<String,Object> snapshot(List<Simulation.Product> products){
        List<Map<String,Object>> stocks=new ArrayList<>(), cells=new ArrayList<>(), batches=new ArrayList<>();
        for(var p:products)stocks.add(Map.of("productId",p.id(),"name",p.name(),"type",p.type(),"stock",stock(p.id()),"reserved",reserved(p.id()),"available",available(p.id())));
        for(int i=0;i<CELL_COUNT;i++){
            final int cell=i;var present=allocations.stream().filter(a->a.cell==cell).toList();
            var content=present.stream().map(a->Map.<String,Object>of("allocationId",a.id,"lotId",a.lotId,"productId",a.productId,"units",a.quantity,"reserved",a.reserved)).toList();
            cells.add(Map.of("id",i+1,"label",label(i),"capacity",CELL_CAPACITY,"used",present.stream().mapToInt(a->a.quantity).sum(),"contents",content));
        }
        for(var lot:lots){
            var locations=allocations.stream().filter(a->a.lotId==lot.id()).map(a->Map.<String,Object>of("label",label(a.cell),"units",a.quantity,"reserved",a.reserved)).toList();
            batches.add(Map.of("id",lot.id(),"receiptId",lot.receiptId(),"productId",lot.productId(),"receivedUnits",lot.receivedUnits(),"locations",locations));
        }
        return Map.of("capacity",CELL_COUNT*CELL_CAPACITY,"occupied",occupied(),"free",free(),"stocks",stocks,"cells",cells,"lots",batches);
    }
}
