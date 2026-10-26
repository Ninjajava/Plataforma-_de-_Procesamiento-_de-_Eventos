import http from 'k6/http';
import {check, sleep} from 'k6';
export const options = {
  scenarios: { purchases: {executor:'shared-iterations',vus:100,iterations:1000,maxDuration:'5m'} },
  thresholds: {'http_req_duration{phase:submit}':['p(95)<1000'],checks:['rate>0.99']},
};
const base=__ENV.BASE_URL || 'http://localhost:8080';
const headers={'Content-Type':'application/json'};
export function setup() {
 const r=http.post(`${base}/api/events`,JSON.stringify({name:'Load test',date:'2027-06-01T01:00:00Z',venue:'Test',capacity:100}),{headers});
 if(r.status!==201) throw new Error(`Cannot create event: ${r.status}`);
 return {event:r.json('id'),run:Date.now()};
}
export default function(data) {
 const key=`load-${data.run}-${__VU}-${__ITER}`;
 const r=http.post(`${base}/api/orders`,JSON.stringify({eventId:data.event,customerId:`user-${__VU}`,quantity:1}),
  {headers:{...headers,'Idempotency-Key':key},tags:{phase:'submit'}});
 check(r,{'submission accepted':r=>r.status===202});
}
export function teardown(data) {
 let state;
 for(let i=0;i<120;i++) {
  const response=http.get(`${base}/api/events/${data.event}/availability`);
  if(response.status!==200) {sleep(1);continue;}
  state=response.json();
  if(state.reserved===100) break;
  sleep(1);
 }
 check(state,{
  'inventory conserved':s=>s && s.capacity===s.available+s.reserved+s.pending+s.sold+s.complimentary,
  'no oversell':s=>s && s.available>=0 && s.reserved===100 && s.sold===0,
 });
}
