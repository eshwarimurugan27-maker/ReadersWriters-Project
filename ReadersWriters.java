import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.*;

/**
 * Project 31: Readers-Writers, Two Priorities Compared
 * Allotted to: ESHWARI M (711525BCS033)
 * Java equivalents of pthreads: Thread, ReentrantLock (mutex), Condition (cond var).
 * Run:  javac ReadersWriters.java && java ReadersWriters
 * Open: http://localhost:8080
 */
public class ReadersWriters {

    // ---------- Monitor: one mutex + two condition variables ----------
    static class Monitor {
        final ReentrantLock mutex = new ReentrantLock();
        final Condition okRead = mutex.newCondition();
        final Condition okWrite = mutex.newCondition();
        final boolean writerPriority;
        int readers = 0, waitingWriters = 0;
        boolean writing = false;

        Monitor(boolean wp) { writerPriority = wp; }

        void startRead() throws InterruptedException {
            mutex.lock();
            try {
                // reader-priority: block only if a writer is ACTIVE
                // writer-priority: also block if any writer is WAITING
                while (writing || (writerPriority && waitingWriters > 0)) okRead.await();
                readers++;
            } finally { mutex.unlock(); }
        }
        void endRead() {
            mutex.lock();
            try { if (--readers == 0) okWrite.signal(); } finally { mutex.unlock(); }
        }
        void startWrite() throws InterruptedException {
            mutex.lock();
            try {
                waitingWriters++;
                while (writing || readers > 0) okWrite.await();
                waitingWriters--;
                writing = true;
            } finally { mutex.unlock(); }
        }
        void endWrite() {
            mutex.lock();
            try {
                writing = false;
                if (writerPriority && waitingWriters > 0) okWrite.signal();
                else { okRead.signalAll(); okWrite.signal(); }
            } finally { mutex.unlock(); }
        }
    }

    // ---------- Simulation (controlled load) ----------
    static String simulate(boolean wp, int nr, int nw, int secs) throws Exception {
        Monitor mon = new Monitor(wp);
        long end = System.nanoTime() + secs * 1_000_000_000L;
        int n = nr + nw;
        long[][] st = new long[n][3]; // ops, maxWaitMs, waitStartNanos (0 = not waiting)
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int id = i; final boolean reader = i < nr;
            Thread t = new Thread(() -> {
                try {
                    while (System.nanoTime() < end) {
                        long t0 = System.nanoTime();
                        st[id][2] = t0;
                        if (reader) mon.startRead(); else mon.startWrite();
                        st[id][2] = 0;
                        st[id][1] = Math.max(st[id][1], (System.nanoTime() - t0) / 1_000_000);
                        Thread.sleep(50);              // critical section work
                        if (reader) mon.endRead(); else mon.endWrite();
                        st[id][0]++;
                        Thread.sleep(10);              // think time
                    }
                } catch (InterruptedException ignored) { }
            });
            ts.add(t); t.start();
        }
        Thread.sleep(secs * 1000L + 300);
        long now = System.nanoTime();
        for (Thread t : ts) t.interrupt();
        for (Thread t : ts) t.join(500);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"mode\":\"").append(wp ? "Writer priority" : "Reader priority").append("\",\"threads\":[");
        long rOps = 0, wOps = 0;
        for (int i = 0; i < n; i++) {
            boolean reader = i < nr;
            long maxWait = st[i][1];
            if (st[i][2] != 0) maxWait = Math.max(maxWait, (now - st[i][2]) / 1_000_000);
            boolean starved = st[i][0] == 0 || maxWait > secs * 1000L / 4;
            if (reader) rOps += st[i][0]; else wOps += st[i][0];
            if (i > 0) sb.append(',');
            sb.append("{\"n\":\"").append(reader ? "R" + (i + 1) : "W" + (i - nr + 1))
              .append("\",\"ops\":").append(st[i][0]).append(",\"max\":").append(maxWait)
              .append(",\"starved\":").append(starved).append('}');
        }
        sb.append("],\"readOps\":").append(rOps).append(",\"writeOps\":").append(wOps).append('}');
        return sb.toString();
    }

    // ---------- Web server ----------
    static final String PAGE = """
<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Readers-Writers Priority Comparison</title>
<style>
body{font-family:system-ui,sans-serif;max-width:900px;margin:0 auto;padding:16px;background:#f5f6fa;color:#222}
h1{font-size:1.3rem}.sub{color:#666;font-size:.85rem}
.card{background:#fff;border-radius:10px;padding:14px;margin:12px 0;box-shadow:0 1px 4px #0002}
label{margin-right:12px;font-size:.9rem}input{width:60px}button{padding:8px 16px;border:0;border-radius:6px;background:#3b5bdb;color:#fff;font-size:1rem}
table{width:100%;border-collapse:collapse}td,th{padding:5px;border-bottom:1px solid #eee;text-align:left;font-size:.9rem}
.bad{color:#c92a2a;font-weight:bold}.ok{color:#2b8a3e}.bar{height:10px;background:#3b5bdb;border-radius:5px}
</style></head><body>
<h1>Readers-Writers: Reader Priority vs Writer Priority</h1>
<div class="sub">Eshwari M (711525BCS033) | Java: Thread + ReentrantLock + Condition</div>
<div class="card">
<label>Readers <input id="r" type="number" value="5"></label>
<label>Writers <input id="w" type="number" value="3"></label>
<label>Seconds <input id="s" type="number" value="5"></label>
<button onclick="go()">Run both</button> <span id="msg"></span></div>
<div id="out"></div>
<script>
async function go(){
 document.getElementById('msg').textContent='Running (about '+2*document.getElementById('s').value+'s)...';
 const q='readers='+r.value+'&writers='+w.value+'&secs='+s.value;
 const d=await (await fetch('/run?'+q)).json();
 document.getElementById('msg').textContent='';
 document.getElementById('out').innerHTML=d.map(m=>{
  const mx=Math.max(1,...m.threads.map(t=>t.ops));
  const starved=m.threads.filter(t=>t.starved).map(t=>t.n).join(', ')||'none';
  return '<div class="card"><b>'+m.mode+'</b><br>Read ops: '+m.readOps+' | Write ops: '+m.writeOps+
  ' | Starved: <span class="'+(starved=='none'?'ok':'bad')+'">'+starved+'</span><table><tr><th>Thread</th><th>Ops</th><th></th><th>Max wait (ms)</th></tr>'+
  m.threads.map(t=>'<tr><td>'+t.n+'</td><td>'+t.ops+'</td><td style="width:40%"><div class="bar" style="width:'+(100*t.ops/mx)+'%"></div></td><td class="'+(t.starved?'bad':'')+'">'+t.max+'</td></tr>').join('')+'</table></div>';
 }).join('');
}
</script></body></html>
""";

    public static void main(String[] a) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress(8080), 0);
        srv.createContext("/", ex -> send(ex, "text/html", PAGE));
        srv.createContext("/run", ex -> {
            try {
                Map<String, Integer> p = new HashMap<>();
                for (String kv : ex.getRequestURI().getQuery().split("&")) {
                    String[] x = kv.split("="); p.put(x[0], Integer.parseInt(x[1]));
                }
                int r = Math.max(1, p.get("readers")), w = Math.max(1, p.get("writers"));
                int s = Math.min(20, Math.max(2, p.get("secs")));
                send(ex, "application/json", "[" + simulate(false, r, w, s) + "," + simulate(true, r, w, s) + "]");
            } catch (Exception e) { send(ex, "text/plain", "error: " + e); }
        });
        srv.start();
        System.out.println("Open http://localhost:8080");
    }

    static void send(HttpExchange ex, String type, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }
}