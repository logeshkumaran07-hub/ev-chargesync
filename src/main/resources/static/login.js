(() => {
  const $ = id => document.getElementById(id);
  const queryRole = new URLSearchParams(location.search).get('role');
  const oauthErrors = {
    provider: 'The provider sign-in did not complete. Check the provider settings and try again.',
    failed: 'ChargeSync could not finish this sign-in. Please try again.',
    'choose-role': 'Choose Driver or Station operator before starting provider sign-in.',
    role: 'This provider account is linked to a different account type. Choose the matching role.',
    unregistered: 'No account is linked to this provider yet. Switch to Create an account to continue.',
    email: 'The provider did not verify this email, so it could not be linked to an existing account.',
    'email-missing': 'The provider did not share an email address. Allow email access and try again.',
    'station-details': 'Enter your station name and area before creating a station account.'
  };
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
  document.querySelectorAll('[data-provider]').forEach(button => button.addEventListener('click', () => beginOAuth(button.dataset.provider, button)));
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
  async function beginOAuth(provider, button) {
    const payload = {provider,role,registering};
    if (registering && role === 'STATION') {
      payload.stationName = $('stationName').value.trim(); payload.stationArea = $('stationArea').value.trim();
      payload.maxOutputKw = Number($('stationKw').value); payload.pricePerKwh = Number($('stationPrice').value);
      if (!payload.stationName || !payload.stationArea) { showError('Enter your station name and area.'); return; }
    }
    document.querySelectorAll('[data-provider]').forEach(item => item.disabled = true);
    $('authError').style.display = 'none';
    try {
      const response = await fetch('/api/auth/oauth/start', {method:'POST',headers:{'Content-Type':'application/json'},credentials:'same-origin',body:JSON.stringify(payload)});
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail || body.message || 'Provider sign-in is not available.');
      location.href = body.authorizationUrl;
    } catch(error) {
      showError(error.message);
      document.querySelectorAll('[data-provider]').forEach(item => item.disabled = false);
    }
  }
  const oauthError = new URLSearchParams(location.search).get('oauthError');
  if (oauthError) showError(oauthErrors[oauthError] || oauthErrors.failed);
  fetch('/api/auth/providers', {credentials:'same-origin'}).then(response => response.ok ? response.json() : {providers:[]}).then(data => {
    const providers = new Set(data.providers || []);
    document.querySelectorAll('[data-provider]').forEach(button => button.classList.toggle('hidden', !providers.has(button.dataset.provider)));
    $('socialSetupNote').classList.toggle('hidden', providers.size > 0);
  }).catch(() => {});
  paint();
})();
