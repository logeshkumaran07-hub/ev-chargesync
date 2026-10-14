(() => {
  const $ = id => document.getElementById(id);
  const queryRole = new URLSearchParams(location.search).get('role');
  let role = queryRole === 'station' ? 'STATION' : 'DRIVER';
  let registering = false;
  const roles = [...document.querySelectorAll('[data-role-choice]')];
  function paint() {
    roles.forEach(button => button.classList.toggle('active', button.dataset.roleChoice === role));
    $('stationFields').classList.toggle('hidden', !registering || role !== 'STATION');
    $('authTitle').textContent = registering ? (role === 'STATION' ? 'Create station account' : 'Create driver account') : 'Welcome back';
    $('authSubtitle').textContent = role === 'STATION' ? 'Manage your station and its published details.' : 'Sign in to plan and reserve a charge.';
    $('submitAuth').textContent = registering ? 'Create account and continue' : 'Sign in';
    $('modePrompt').textContent = registering ? 'Already have an account?' : 'New to ChargeSync?';
    $('modeSwitch').textContent = registering ? 'Sign in' : 'Create an account';
    $('password').autocomplete = registering ? 'new-password' : 'current-password';
  }
  roles.forEach(button => button.addEventListener('click', () => { role = button.dataset.roleChoice; $('authError').style.display = 'none'; paint(); }));
  $('modeSwitch').addEventListener('click', () => { registering = !registering; $('authError').style.display = 'none'; paint(); });
  $('authForm').addEventListener('submit', async event => {
    event.preventDefault();
    const payload = {email:$('email').value.trim(),password:$('password').value,role};
    if (registering && role === 'STATION') {
      payload.stationName = $('stationName').value.trim(); payload.stationArea = $('stationArea').value.trim();
      payload.maxOutputKw = Number($('stationKw').value); payload.pricePerKwh = Number($('stationPrice').value);
      if (!payload.stationName || !payload.stationArea) { showError('Enter your station name and area.'); return; }
    }
    $('submitAuth').disabled = true; $('submitAuth').textContent = registering ? 'Creating account…' : 'Signing in…';
    try {
      const response = await fetch(`/api/auth/${registering?'register':'login'}`, {method:'POST',headers:{'Content-Type':'application/json'},credentials:'same-origin',body:JSON.stringify(payload)});
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail || body.message || 'Could not sign in.');
      location.href = body.role === 'STATION' ? '/station.html' : '/';
    } catch(error) { showError(error.message); paint(); }
    finally { $('submitAuth').disabled = false; }
  });
  function showError(message) { $('authError').textContent = message; $('authError').style.display = 'block'; }
  paint();
})();
