(() => {
  const page = document.body.dataset.page;
  let stations = [];
  let bookings = [];
  let selectedStationId = '';
  let stationDraft = null;
  let refreshBusy = false;
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const number = (n, digits = 1) => Number(n || 0).toLocaleString(undefined, {maximumFractionDigits:digits});
  const active = b => ['CHARGING','WAITING_FOR_POWER'].includes(b.status);
  const pill = (b) => {
    const value = (b.status || 'BOOKED').replaceAll('_',' ');
    const cls = b.priority === 'HIGH' ? 'warn' : '';
    return `<span class="pill ${cls}">${esc(value)}</span>`;
  };
  const toast = (message) => {
    const el = $('toast'); if (!el) return;
    el.textContent = message; el.classList.add('show');
    clearTimeout(toast.timer); toast.timer = setTimeout(() => el.classList.remove('show'), 3600);
  };
  async function api(path, options = {}) {
    const response = await fetch(path, {headers:{'Content-Type':'application/json',...(options.headers || {})},...options});
    if (!response.ok) {
      let message = `Request failed (${response.status})`;
      try { const body = await response.json(); message = body.detail || body.message || message; } catch {}
      throw new Error(message);
    }
    if (response.status === 204) return null;
    return response.json();
  }
  async function refresh() {
    if (refreshBusy) return;
    refreshBusy = true;
    try {
      const [nextStations, nextBookings] = await Promise.all([api('/api/portal/stations'), api('/api/portal/bookings')]);
      stations = nextStations; bookings = nextBookings;
      if (page === 'driver') renderDriverData();
      if (page === 'station') renderStationData(false);
    } catch (error) {
      const targets = page === 'driver' ? ['stationList','driverBookings'] : ['stationStats','stationBookings'];
      for (const id of targets) if ($(id)) $(id).innerHTML = `<div class="empty">Could not reach the ChargeSync service. Start the app using run-windows.bat, then refresh this page.</div>`;
    } finally { refreshBusy = false; }
  }

  function renderDriverData() {
    const selected = $('currentStation')?.value;
    $('currentStation').innerHTML = stations.map(s => `<option value="${esc(s.id)}">${esc(s.name)} · ${s.availableDocks} open</option>`).join('');
    if (selected && stations.some(s => s.id === selected)) $('currentStation').value = selected;
    $('stationList').innerHTML = stations.map(s => {
      const available = s.availableDocks > 0;
      return `<article class="station-card"><div><h3>${esc(s.name)} <span class="pill ${available?'':'warn'}">${available ? `${s.availableDocks} dock${s.availableDocks===1?'':'s'} open` : 'Full'}</span></h3><p>${esc(s.area)} · ${number(s.latitude,4)}, ${number(s.longitude,4)}</p></div><div class="station-stats"><span>${number(s.allocatedKw)} / ${number(s.maxOutputKw)} kW</span><span>₹${number(s.pricePerKwh,2)}/kWh</span></div></article>`;
    }).join('') || '<div class="empty">No stations have been published yet.</div>';
    $('driverBookings').innerHTML = bookings.length ? bookings.slice().reverse().map(b => `
      <article class="booking"><div class="booking-top"><strong>${esc(b.id)} · ${esc(b.driverName)}</strong>${pill(b)}</div>
      <p>${esc(b.destination)} · ${esc(b.assignedStationName)} / dock ${esc(b.assignedDockId)}${b.redirected ? ` · redirected ${number(b.distanceKm,1)} km` : ''}<br>${esc(b.chargeMode || 'NORMAL')} speed · battery ${number(b.batteryPercent,0)}% → ${number(b.targetSoc,0)}% · ${number(b.allocatedKw)} kW allocated · ${number(b.departureMinutes,0)} min to departure<br>Estimate ${b.estimatedMinutes >= 0 ? `${number(b.estimatedMinutes)} min` : 'waiting for power'} · ₹${number(b.estimatedCostInr,2)} remaining energy</p>
      ${active(b) ? `<div class="actions"><button class="btn danger" data-cancel="${esc(b.id)}">Cancel booking</button></div>` : ''}</article>`).join('') : '<div class="empty">Your booking confirmations will appear here.</div>';
  }

  function setLocationMode() {
    const coordinates = $('locationMode').value === 'coordinates';
    $('stationLocationField').style.display = coordinates ? 'none' : '';
    $('latField').style.display = coordinates ? '' : 'none';
    $('lonField').style.display = coordinates ? '' : 'none';
    $('currentStation').required = !coordinates;
    $('currentLatitude').required = coordinates;
    $('currentLongitude').required = coordinates;
  }
  async function submitBooking(event) {
    event.preventDefault();
    const button = $('bookButton'); button.disabled = true; button.textContent = 'Checking stations…';
    const coordinates = $('locationMode').value === 'coordinates';
    const body = {
      driverName:$('driverName').value.trim(), destination:$('destination').value.trim(),
      connector:$('connector').value, chargeMode:$('chargeMode').value, currentStationId:coordinates ? '' : $('currentStation').value,
      currentLatitude:coordinates ? Number($('currentLatitude').value) : null,
      currentLongitude:coordinates ? Number($('currentLongitude').value) : null,
      departureMinutes:Number($('departureMinutes').value), batteryPercent:Number($('batteryPercent').value),
      batteryCapacityKwh:Number($('batteryCapacityKwh').value), targetSoc:Number($('targetSoc').value),
      vehicleMaxKw:Number($('vehicleMaxKw').value), estimatedTripKm:Number($('estimatedTripKm').value),
      rangeAt100Km:Number($('rangeAt100Km').value)
    };
    try {
      const b = await api('/api/portal/bookings', {method:'POST',body:JSON.stringify(body)});
      const result = $('bookingResult'); result.className = `result show ${b.redirected ? 'warn' : ''}`;
      result.innerHTML = `<h3>${b.redirected ? 'A nearby station has an open dock' : 'Your charging slot is reserved'}</h3><p>${esc(b.routeMessage)} Destination recorded: ${esc(b.destination)}. Go to <strong>${esc(b.assignedStationName)}</strong>, dock <strong>${esc(b.assignedDockId)}</strong> (${esc(b.connector)}). ${esc(b.chargeMode)} speed selected. ${b.canMakeTrip ? 'The current estimate reaches the trip target before departure.' : 'The estimate may not reach the trip target before departure; power may change as other sessions update.'}</p>
        <div class="metrics"><div class="metric"><span>Live power allocation</span><strong>${number(b.allocatedKw)} kW</strong></div><div class="metric"><span>Approx. station distance</span><strong>${number(b.distanceKm,1)} km</strong></div><div class="metric"><span>Charge estimate</span><strong>${b.estimatedMinutes >= 0 ? `${number(b.estimatedMinutes)} min` : 'Waiting'}</strong></div><div class="metric"><span>Price / remaining energy</span><strong>₹${number(b.pricePerKwh,2)} / ₹${number(b.estimatedCostInr,2)}</strong></div></div>
        <p style="margin-top:12px">Trip reserve included: ${number(b.requiredTripSoc,0)}% · target used: ${number(b.targetSoc,0)}% · priority: ${esc(b.priority)} (rank ${b.priorityRank || '—'}).</p>`;
      await refresh();
      result.scrollIntoView({behavior:'smooth',block:'nearest'});
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; button.innerHTML = 'Find a dock and book&nbsp; →'; }
  }

  function stationById(id = selectedStationId) { return stations.find(s => s.id === id); }
  function renderStationData(renderEditor) {
    const select = $('stationSelect');
    if (!select) return;
    const currentValue = select.value && select.value !== '__new' ? select.value : selectedStationId;
    select.innerHTML = stations.map(s => `<option value="${esc(s.id)}">${esc(s.name)} · ${esc(s.area)}</option>`).join('') + '<option value="__new">＋ New station…</option>';
    if (stationDraft) select.value = '__new';
    else if (stations.some(s => s.id === currentValue)) { select.value = currentValue; selectedStationId = currentValue; }
    else if (stations.length) { selectedStationId = stations[0].id; select.value = selectedStationId; }
    if (renderEditor) fillStationEditor();
    const station = stationById();
    if (!station) {
      $('stationStats').innerHTML = '<div class="empty">Save a station to publish it to drivers.</div>';
      $('stationBookings').innerHTML = '<div class="empty">No station selected.</div>';
      return;
    }
    $('stationStats').innerHTML = `<div class="statcard"><span>Live output</span><strong>${number(station.allocatedKw)}<small style="font-size:12px;color:#687b75"> / ${number(station.maxOutputKw)} kW</small></strong></div><div class="statcard"><span>Open docks</span><strong>${station.availableDocks}<small style="font-size:12px;color:#687b75"> / ${station.docks.length}</small></strong></div><div class="statcard"><span>Active sessions</span><strong>${station.activeBookings}</strong></div><div class="statcard"><span>Tariff</span><strong>₹${number(station.pricePerKwh,2)}<small style="font-size:12px;color:#687b75"> / kWh</small></strong></div>`;
    $('stationBookings').innerHTML = bookings.filter(b => b.assignedStationId === station.id).length
      ? bookings.filter(b => b.assignedStationId === station.id).slice().reverse().map(b => `<article class="booking"><div class="booking-top"><strong>${esc(b.id)} · ${esc(b.driverName)}</strong>${pill(b)}</div><p>${esc(b.destination)} · dock ${esc(b.assignedDockId)} · ${esc(b.connector)} · ${esc(b.chargeMode || 'NORMAL')} speed<br>Battery ${number(b.batteryPercent,0)}% → ${number(b.targetSoc,0)}% · ${number(b.allocatedKw)} kW · ${number(b.departureMinutes,0)} min to departure<br>Delivered ${number(b.energyDeliveredKwh)} kWh · session cost ₹${number(b.costInr,2)} · est. remaining ₹${number(b.estimatedCostInr,2)}</p>${active(b) ? `<div class="actions"><button class="btn secondary" data-complete="${esc(b.id)}">Complete session</button><button class="btn danger" data-cancel="${esc(b.id)}">Cancel booking</button></div>` : ''}</article>`).join('')
      : '<div class="empty">No bookings at this station yet. New driver bookings will appear here.</div>';
  }
  function fillStationEditor() {
    const station = stationDraft || stationById();
    const fresh = !!stationDraft;
    $('settingsTitle').textContent = fresh ? 'New station details' : 'Station settings';
    $('saveStationButton').textContent = fresh ? 'Publish station' : 'Save station settings';
    $('stationName').value = station?.name || '';
    $('stationArea').value = station?.area || '';
    $('stationLatitude').value = station?.latitude ?? '';
    $('stationLongitude').value = station?.longitude ?? '';
    $('stationMaxOutput').value = station?.maxOutputKw ?? 60;
    $('stationPrice').value = station?.pricePerKwh ?? 18;
    const docks = station?.docks || [];
    $('dockRows').innerHTML = docks.map(d => {
      const busy = !!d.bookingId;
      const status = busy ? d.status : (d.status || 'AVAILABLE');
      return `<div class="dock-row" data-dock-id="${esc(d.id)}"><div><strong>${esc(d.id)}</strong><small>${busy ? `Booked · ${esc(d.sessionLabel || '')}` : 'Dock status'}</small></div><div><span class="mini-label">Connector</span><select data-connector ${busy?'disabled':''}><option ${d.connector==='CCS2'?'selected':''}>CCS2</option><option ${d.connector==='Type 2'?'selected':''}>Type 2</option><option ${d.connector==='CHADEMO'?'selected':''}>CHAdeMO</option></select></div><div><span class="mini-label">Limit (kW)</span><input data-maxkw type="number" min="1" max="500" value="${Number(d.maxKw)}" ${busy?'disabled':''}></div><div><span class="mini-label">${busy?'Live status':'Availability'}</span>${busy?`<span class="pill ${d.status==='CHARGING'?'':'warn'}">${esc(d.status.replaceAll('_',' '))}</span>`:`<select data-status><option ${status==='AVAILABLE'?'selected':''}>AVAILABLE</option><option ${status==='OCCUPIED'?'selected':''}>OCCUPIED</option><option ${status==='MAINTENANCE'?'selected':''}>MAINTENANCE</option></select>`}</div><div><button type="button" class="btn danger" data-remove-dock="${esc(d.id)}" ${busy?'disabled title="Complete or cancel its booking first"':''}>Remove</button></div></div>`;
    }).join('') || '<div class="empty">No docks added yet.</div>';
    $('hardwareStatus').textContent = station?.hardwareLink || 'Demo dock connector · no physical charger attached.';
  }
  function beginNewStation() {
    stationDraft = {name:'',area:'',latitude:12,longitude:77,maxOutputKw:60,pricePerKwh:18,docks:[]};
    $('stationSelect').value = '__new'; fillStationEditor(); renderStationData(false);
  }
  function collectStationBody() {
    const existing = stationDraft || stationById();
    const docks = [...$('dockRows').querySelectorAll('.dock-row')].map(row => {
      const prev = (existing?.docks || []).find(d => d.id === row.dataset.dockId) || {};
      return {id:row.dataset.dockId,connector:row.querySelector('[data-connector]')?.value || prev.connector,
        maxKw:Number(row.querySelector('[data-maxkw]')?.value || prev.maxKw),
        status:row.querySelector('[data-status]')?.value || prev.status || 'AVAILABLE'};
    });
    return {name:$('stationName').value.trim(),area:$('stationArea').value.trim(),latitude:Number($('stationLatitude').value),longitude:Number($('stationLongitude').value),maxOutputKw:Number($('stationMaxOutput').value),pricePerKwh:Number($('stationPrice').value),docks};
  }
  async function saveStation(event) {
    event.preventDefault();
    const button = $('saveStationButton'); button.disabled = true;
    try {
      const body = collectStationBody();
      const result = stationDraft
        ? await api('/api/portal/stations',{method:'POST',body:JSON.stringify(body)})
        : await api(`/api/portal/stations/${encodeURIComponent(selectedStationId)}`,{method:'PUT',body:JSON.stringify(body)});
      stationDraft = null; selectedStationId = result.id; await refresh(); fillStationEditor(); toast('Station settings are live in the driver portal.');
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; }
  }
  async function changeDockStatus(stationId, dockId, status) {
    try { await api(`/api/portal/stations/${encodeURIComponent(stationId)}/docks/${encodeURIComponent(dockId)}/status`,{method:'PUT',body:JSON.stringify({status})}); await refresh(); fillStationEditor(); toast(`Dock ${dockId} updated.`); }
    catch (error) { toast(error.message); }
  }
  async function updateBooking(id, action) {
    try { await api(`/api/portal/bookings/${encodeURIComponent(id)}/${action}`,{method:'POST',body:'{}'}); await refresh(); if(page==='station') fillStationEditor(); toast(action==='cancel'?'Booking cancelled.':'Charging session completed.'); }
    catch (error) { toast(error.message); }
  }

  if (page === 'driver') {
    $('locationMode').addEventListener('change',setLocationMode);
    $('bookingForm').addEventListener('submit',submitBooking);
    $('driverBookings').addEventListener('click',e=>{const b=e.target.closest('[data-cancel]');if(b)updateBooking(b.dataset.cancel,'cancel');});
    setLocationMode(); refresh(); setInterval(refresh,5000);
  }
  if (page === 'station') {
    $('stationSelect').addEventListener('change',e=>{
      if(e.target.value==='__new'){beginNewStation();return;}
      stationDraft=null; selectedStationId=e.target.value; renderStationData(true);
    });
    $('newStationButton').addEventListener('click',beginNewStation);
    $('stationForm').addEventListener('submit',saveStation);
    $('addDockButton').addEventListener('click',()=>{
      const existing=stationDraft||stationById(); if(!existing){toast('Choose or add a station first.');return;}
      const id=$('newDockId').value.trim() || `D-${String(existing.docks.length+1).padStart(2,'0')}`;
      if(existing.docks.some(d=>d.id===id)){toast('That dock ID is already in use.');return;}
      existing.docks.push({id,connector:$('newDockConnector').value,maxKw:Number($('newDockKw').value),status:$('newDockStatus').value,bookingId:null});
      $('newDockId').value=''; fillStationEditor();
    });
    $('dockRows').addEventListener('change',e=>{
      const row=e.target.closest('.dock-row'); if(!row||!e.target.matches('[data-status]'))return;
      if(stationDraft){const d=stationDraft.docks.find(x=>x.id===row.dataset.dockId);if(d)d.status=e.target.value;return;}
      changeDockStatus(selectedStationId,row.dataset.dockId,e.target.value);
    });
    $('dockRows').addEventListener('click',e=>{
      const button=e.target.closest('[data-remove-dock]');if(!button)return;
      const station=stationDraft||stationById(); if(station){station.docks=station.docks.filter(d=>d.id!==button.dataset.removeDock);fillStationEditor();}
    });
    $('stationBookings').addEventListener('click',e=>{const cancel=e.target.closest('[data-cancel]');const done=e.target.closest('[data-complete]');if(cancel)updateBooking(cancel.dataset.cancel,'cancel');if(done)updateBooking(done.dataset.complete,'complete');});
    $('advanceButton').addEventListener('click',async()=>{try{await api('/api/portal/advance',{method:'POST',body:'{}'});await refresh();fillStationEditor();toast('Demo sessions advanced by five minutes.');}catch(error){toast(error.message);}});
    refresh().then(()=>renderStationData(true)); setInterval(refresh,5000);
  }
})();
