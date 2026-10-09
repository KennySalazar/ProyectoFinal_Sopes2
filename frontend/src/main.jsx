import React, {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import './style.css';
const api=import.meta.env.VITE_API_URL || 'http://localhost:8090/api';
function App(){
 const [data,setData]=useState(null),[error,setError]=useState(''),[level,setLevel]=useState(3),[units,setUnits]=useState(5);
 async function refresh(){try{const r=await fetch(api+'/state');if(!r.ok)throw Error('API no disponible');setData(await r.json());setError('');}catch(e){setError(e.message);}}
 useEffect(()=>{refresh();const t=setInterval(refresh,700);return ()=>clearInterval(t);},[]);
 async function action(path){try{const r=await fetch(api+path,{method:'POST'});if(!r.ok)throw Error((await r.json()).error);await refresh();}catch(e){setError(e.message);}}
 return <main><header><span>SO2 / SIMULACION CONCURRENTE</span><h1>REDXela</h1><p>Centro de distribucion · Base funcional 0.1</p></header><aside>Esta version demuestra planificacion y 6 estaciones de empaque. Inventario, cuentas, otros recursos y deadlock estan pendientes.</aside>{error&&<aside className="error">{error}</aside>}
 <section className="metrics">{[['Completados',data?.completed??0],['Empaque disponible',`${data?.availablePacking??6} / 6`],['Espera promedio',`${((data?.averageWaitMs??0)/1000).toFixed(1)} s`],['Proceso promedio',`${((data?.averageProcessMs??0)/1000).toFixed(1)} s`]].map(([k,v])=><article key={k}><small>{k}</small><h2>{v}</h2></article>)}</section>
 <section className="controls"><form onSubmit={e=>{e.preventDefault();action(`/orders?level=${level}&units=${units}`);}}><h3>Pedido manual</h3><label>Servicio <select value={level} onChange={e=>setLevel(Number(e.target.value))}>{['Expres','Prioritario','Estandar','Programado','Economico'].map((v,i)=><option key={v} value={i+1}>{i+1} · {v}</option>)}</select></label><label>Unidades <input type="number" min="1" max="1000" required value={units} onChange={e=>setUnits(e.target.value)}/></label><button>Crear pedido</button></form><article><h3>Planificador</h3><select value={data?.policy||'PRIORIDAD'} onChange={e=>action('/policy?value='+e.target.value)}><option>PRIORIDAD</option><option>FIFO</option><option>UNIDADES</option></select><p>Las politicas ordenan pedidos en espera; no interrumpen los activos.</p><button onClick={()=>action('/generator?enabled='+!data?.automatic)}>{data?.automatic?'Detener':'Iniciar'} generador</button><p>1 segundo real por llegada. 0.5 segundos por unidad.</p></article></section>
 <section><h3>Pedidos y cola · {data?.policy}</h3><div className="table"><table><thead><tr><th>ID</th><th>Nivel</th><th>Unidades</th><th>Estado</th><th>Progreso</th><th>Restante</th></tr></thead><tbody>{data?.orders.map(o=><tr key={o.id}><td>#{o.id}</td><td>{o.level}</td><td>{o.units}</td><td>{o.state}</td><td><progress max="100" value={o.progress}/> {o.progress}%</td><td>{(o.remainingMs/1000).toFixed(1)} s</td></tr>)}</tbody></table></div></section></main>;
}
createRoot(document.getElementById('root')).render(<App/>);
