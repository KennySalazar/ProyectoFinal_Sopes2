import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Adaptador HTTP: valida entrada y delega concurrencia al motor. */
public final class Main {
    private static final Simulation simulation=new Simulation();
    private static Map<String,String> query(HttpExchange e) {
        Map<String,String> m=new HashMap<>();String q=e.getRequestURI().getRawQuery();
        if(q!=null)for(String pair:q.split("&")){String[] kv=pair.split("=",2);if(kv.length==2)m.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
        return m;
    }
    private static void reply(HttpExchange e,int code,Object value) throws java.io.IOException {
        byte[] bytes=Json.encode(value).getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        e.sendResponseHeaders(code,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}
    }
    private static void handle(HttpExchange e) throws java.io.IOException {
        e.getResponseHeaders().set("Access-Control-Allow-Origin","*");
        e.getResponseHeaders().set("Access-Control-Allow-Methods","GET, POST, OPTIONS");
        if(e.getRequestMethod().equals("OPTIONS")){e.sendResponseHeaders(204,-1);e.close();return;}
        try {
            String path=e.getRequestURI().getPath();var q=query(e);
            if(path.equals("/api/state")&&e.getRequestMethod().equals("GET")){reply(e,200,simulation.snapshot());return;}
            if(!List.of("/api/orders","/api/policy","/api/generator").contains(path)){reply(e,404,Map.of("error","Ruta no encontrada"));return;}
            if(!e.getRequestMethod().equals("POST")){reply(e,405,Map.of("error","Usa POST"));return;}
            switch(path) {
                case "/api/orders" -> {
                    List<Simulation.Line> lines=new ArrayList<>();String items=q.get("items");
                    if(items==null)throw new IllegalArgumentException("Indica productos: items=1:5,2:3");
                    if(items.length()>300)throw new IllegalArgumentException("Demasiadas lineas");
                    for(String item:items.split(",",-1)) {
                        String[] pair=item.split(":",-1);if(pair.length!=2)throw new IllegalArgumentException("Formato items invalido");
                        lines.add(new Simulation.Line(Integer.parseInt(pair[0]),Integer.parseInt(pair[1])));
                    }
                    reply(e,201,simulation.create(Integer.parseInt(q.getOrDefault("level","3")),lines));
                }
                case "/api/policy" -> {simulation.setPolicy(q.getOrDefault("value",""));reply(e,200,Map.of("ok",true));}
                case "/api/generator" -> {String v=q.getOrDefault("enabled","");if(!List.of("true","false").contains(v))throw new IllegalArgumentException("enabled debe ser true o false");simulation.setAutomatic(Boolean.parseBoolean(v));reply(e,200,Map.of("ok",true));}
                default -> reply(e,404,Map.of("error","Ruta no encontrada"));
            }
        } catch(IllegalArgumentException ex){reply(e,400,Map.of("error",ex.getMessage()==null?"Parametros invalidos":ex.getMessage()));}
        catch(Exception ex){ex.printStackTrace();reply(e,500,Map.of("error","Error interno"));}
        finally{e.close();}
    }
    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(System.getenv().getOrDefault("PORT","8090"));
        HttpServer server=HttpServer.create(new InetSocketAddress(port),0);
        ExecutorService httpWorkers=Executors.newFixedThreadPool(8);
        server.createContext("/api",Main::handle);server.setExecutor(httpWorkers);server.start();simulation.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);simulation.close();httpWorkers.shutdownNow();}));
        System.out.println("REDXela 0.2 API: http://localhost:"+port+"/api/state");
    }
}
