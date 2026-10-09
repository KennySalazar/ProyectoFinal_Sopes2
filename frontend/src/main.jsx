import React, {useEffect, useRef, useState} from 'react';
import {createRoot} from 'react-dom/client';
import './style.css';
const api=import.meta.env.VITE_API_URL || 'http://localhost:8090/api';
const services=['Expres','Prioritario','Estandar','Programado','Economico'];
const seconds=ms=>(ms/1000).toFixed(1)+' s';
const readable=s=>s.replaceAll('_',' ');
function OrderTable({orders,waiting=false}){
 if(!orders.length)return <p className="empty">No hay pedidos en esta seccion.</p>;
 return <div className="table"><table><thead><tr>{waiting&&<th>Orden</th>}<th>Pedido / productos</th><th>Servicio</th><th>Etapa</th><th>Progreso</th><th>Trabajo restante</th><th>{waiting?'Motivo de espera':'Recursos / resultado'}</th></tr></thead><tbody>{orders.map((o,index)=><tr key={o.id}>{waiting&&<td>{index+1}</td>}<td><strong>#{o.id}</strong><small>{o.lines.map(l=>`${l.name} x${l.units}`).join(', ')}</small><small>{o.units} unidades totales</small></td><td><span className={'badge level'+o.level}>{o.level} · {services[o.level-1]}</span></td><td>{readable(o.stage)}<small>{readable(o.state)}</small></td><td><progress max="100" value={o.progress}/><small>{o.progress}%</small></td><td>{seconds(o.remainingMs)}<small>Espera: {seconds(o.waitMs)}</small></td><td>{waiting?o.reason:(o.heldResources.map(readable).join(', ')||readable(o.state))}</td></tr>)}</tbody></table></div>;
}
function App(){
 const [data,setData]=useState(null),[connection,setConnection]=useState(''),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const [level,setLevel]=useState(3),[lines,setLines]=useState([{productId:1,units:5}]),[busy,setBusy]=useState(false),[tab,setTab]=useState('operacion');
 const sequence=useRef(0),mounted=useRef(true);
 async function refresh(){
  const request=++sequence.current;
  try{const r=await fetch(api+'/state');if(!r.ok)throw Error('API no disponible');const state=await r.json();if(mounted.current&&request===sequence.current){setData(state);setConnection('');}}
  catch(e){if(mounted.current&&request===sequence.current)setConnection('Sin conexion al backend: '+e.message);}
 }
 useEffect(()=>{mounted.current=true;refresh();const t=setInterval(refresh,700);return ()=>{mounted.current=false;clearInterval(t);};},[]);
 async function action(path,message=''){
  setBusy(true);setError('');setNotice('');
  try{const r=await fetch(api+path,{method:'POST'});const body=await r.json();if(!r.ok)throw Error(body.error||'Operacion rechazada');setNotice(message);await refresh();}
  catch(e){setError(e.message);}finally{setBusy(false);}
 }
 function update(index,key,value){setLines(current=>current.map((l,i)=>i===index?{...l,[key]:Number(value)}:l));}
 const resources=data?.resources||[], active=data?.active||[], waiting=data?.waiting||[], history=data?.history||[];
 return <main><header><div><span>SO2 / HILOS Y RECURSOS COMPARTIDOS</span><h1>REDXela</h1><p>Centro de distribucion · Version 0.2</p></div><div className="status">{connection?'SIN CONEXION':data?'API CONECTADA':'CONECTANDO'}</div></header>
 <aside>Etapa 2: catalogo y recursos reales. El stock se asume disponible. Cuentas, recepcion, inventario, PostgreSQL y demostracion de deadlock siguen pendientes.</aside>
 {connection&&<aside className="error" role="alert">{connection}</aside>}{error&&<aside className="error" role="alert">{error}</aside>}{notice&&<div className="success" role="status">{notice}</div>}
 <section className="metrics">{[['Completados',data?.completed??0],['En proceso',data?.activeCount??0],['En espera',data?.waitingCount??0],['Espera promedio',seconds(data?.averageWaitMs??0)],['Proceso promedio',seconds(data?.averageProcessMs??0)]].map(([k,v])=><article key={k}><small>{k}</small><h2>{v}</h2></article>)}</section>
 <nav>{[['operacion','Operacion'],['recursos','Recursos'],['catalogo','Catalogo'],['historial','Historial']].map(([key,label])=><button key={key} className={tab===key?'selected':'secondary'} onClick={()=>setTab(key)}>{label}</button>)}</nav>
 {tab==='operacion'&&<><section className="controls"><form onSubmit={e=>{e.preventDefault();const items=lines.map(l=>`${l.productId}:${l.units}`).join(',');action('/orders?'+new URLSearchParams({level:String(level),items}),'Pedido registrado');}}><h3>Nuevo pedido</h3><label>Servicio<select value={level} onChange={e=>setLevel(Number(e.target.value))}>{services.map((v,i)=><option key={v} value={i+1}>{i+1} · {v}</option>)}</select></label>
 {lines.map((l,i)=><div className="order-line" key={i}><label>Producto<select value={l.productId} onChange={e=>update(i,'productId',e.target.value)}>{data?.products.map(p=><option key={p.id} value={p.id}>{p.name} · {p.type}</option>)}</select></label><label>Unidades<input aria-label={'Unidades de linea '+(i+1)} type="number" min="1" max="1000" required value={l.units} onChange={e=>update(i,'units',e.target.value)}/></label><button type="button" className="secondary" aria-label={'Eliminar linea '+(i+1)} disabled={lines.length===1} onClick={()=>setLines(lines.filter((_,index)=>index!==i))}>Quitar</button></div>)}
 <div className="actions"><button type="button" className="secondary" disabled={lines.length>=10} onClick={()=>setLines([...lines,{productId:1,units:1}])}>Agregar producto</button><button disabled={busy||!data||!!connection}>Crear pedido</button></div><p className="hint">Maximo 1000 unidades por pedido. Los productos repetidos se consolidan.</p></form>
 <article><h3>Planificador</h3><label>Politica<select disabled={busy||!data||!!connection} value={data?.policy||'PRIORIDAD'} onChange={e=>action('/policy?value='+e.target.value)}><option>PRIORIDAD</option><option>FIFO</option><option>UNIDADES</option></select></label><p>{data?.policy==='FIFO'?'Atiende por orden de llegada.':data?.policy==='UNIDADES'?'Atiende primero el pedido con menos unidades.':'Atiende primero el nivel menor: 1 antes de 2, hasta 5.'}</p><p>Las etapas activas terminan sin interrupcion. Si un pedido no consigue sus recursos, el motor considera los siguientes de la cola.</p><button disabled={busy||!data||!!connection} onClick={()=>action('/generator?enabled='+!data?.automatic)}>{data?.automatic?'Detener':'Iniciar'} generador</button><p className="hint">Un pedido por segundo, servicios 1-5 y productos de los cinco tipos. Duracion proporcional a unidades.</p><div className="legend"><strong>Flujo</strong><p>Preparacion → empaque si aplica → calidad si aplica → escaneo → carga.</p><small>El trabajo restante excluye esperas futuras. Los promedios corresponden a pedidos completados y suman todas sus etapas.</small></div></article></section>
 <section className="panel"><h3>Procesando <span>{active.length}</span></h3><OrderTable orders={active}/></section><section className="panel"><h3>Lista de espera · {data?.policy} <span>{waiting.length}</span></h3><OrderTable orders={waiting} waiting/></section></>}
 {tab==='recursos'&&<section className="resource-grid">{resources.map(r=><article key={r.key}><div className="resource-title"><h3>{r.name}</h3><span className="badge">{r.available} / {r.capacity} libres</span></div><div className="slots">{r.slots.map(s=><div className={'slot '+(s.owner?'occupied':'')} key={s.slot}><strong>Unidad {s.slot}</strong><small>{s.owner?'Pedido #'+s.owner:'Disponible'}</small></div>)}</div><p className="hint">Ocupacion acumulada: {r.occupancyPercent.toFixed(1)}%</p></article>)}</section>}
 {tab==='catalogo'&&<><section className="panel"><h3>Tipos de mercancia</h3><p>Combinaciones especificas por tipo. Todos los pedidos usan escaner y carga durante el despacho.</p><div className="type-grid">{data?.types.map(t=><article key={t.name}><h3>{t.name}</h3><p>{t.description}</p><small>{t.needs.join(' + ')}</small></article>)}</div></section><section className="panel"><h3>Productos predefinidos</h3><div className="table"><table><thead><tr><th>ID</th><th>Producto</th><th>Tipo</th></tr></thead><tbody>{data?.products.map(p=><tr key={p.id}><td>{p.id}</td><td>{p.name}</td><td>{p.type}</td></tr>)}</tbody></table></div></section></>}
 {tab==='historial'&&<section className="panel"><h3>Ultimos 100 pedidos finalizados</h3><p>El historial se pierde al reiniciar esta version.</p><OrderTable orders={history}/></section>}
 <footer>Tiempo real · Simulacion de trabajo por hilos Java · Recursos liberados al terminar cada etapa</footer></main>;
}
createRoot(document.getElementById('root')).render(<App/>);
