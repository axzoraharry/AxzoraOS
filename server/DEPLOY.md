# Deploying the Axzora server permanently

Right now the backend runs on this laptop and is exposed through a Cloudflare
quick tunnel. That works anywhere, but the URL dies when the tunnel restarts and
the laptop has to stay awake. This guide moves it to a host that runs 24/7.

**You do not need to rebuild the APK when the server moves.** The app probes
`Config.SERVER_CANDIDATES` and keeps whichever address answers, so adding the new
URL to that list (for future builds) is the only app-side change — existing
installs keep working as long as one listed address is alive.

---

## Option A — Render (recommended: laptop can be off)

Free, no card required.

1. **Put the server folder in a GitHub repo.**
   Create an empty repo on github.com, then in this folder:

```bash
cd "C:\Axzora\work on android customized os\AxzoraOS\server" && git init && git add .   # .gitignore here already excludes wallet/ledger/state/.env.local/admin-code.json && git commit -m "Axzora server" && git branch -M main && git remote add origin https://github.com/YOURNAME/axzora-server.git && git push -u origin main
```

2. Go to **render.com** → sign in with GitHub → **New → Web Service** → pick the repo.
3. Settings Render should detect automatically (confirm them):
   - Runtime: **Node**
   - Build command: *(leave empty)*
   - Start command: `node server.js`
   - Instance type: **Free**
4. Deploy. You get a permanent URL like `https://axzora-server.onrender.com`.
5. Put that URL **first** in `app/src/main/java/com/axzora/os/Config.kt`
   (`SERVER_CANDIDATES`) and rebuild the APK for future installs.

### The admin code is an environment variable, and there is no default

`AXZORA_ADMIN_CODE` is the only thing that unlocks the admin console. There is no
`admin-code.json` any more and no fallback in the source: if the variable is unset,
`/admin/*` answers `503 admin not configured` and the console stays shut. That is
deliberate — the old fallback was a literal string that shipped inside the APK, so
"unset" used to mean "open to anyone who read the repo".

- **Render:** set it once under *Environment* (declared in `render.yaml`).
- **This device / laptop:** put it in `.env.local` beside `server.js` — the file
  is gitignored and already present as an empty template. `start-server.sh`
  (via `supervisor.sh`) and `start-server.bat` load it automatically.
- **Windows `cmd`:** `set AXZORA_ADMIN_CODE=...` before `node server.js`.

If the code leaks, generate a new one, update it in both places and restart. Every
claimed admin seat is checked against the current code, so the old value stops
working on the next request.

"admin-code.json" copies from before this change are public: it was committed to
the repo and one copy is still readable on GitHub by commit SHA. Rotating the code
is the fix; deleting the file is not.

**Free tier caveat:** the service sleeps after ~15 minutes with no traffic, and
the next request takes ~50 seconds to wake it. Fine for family use; the app
retries. Paid tier removes it.

**Data caveat:** Render's free disk is wiped on every deploy and restart, so
`families.json` / `wallet.json` reset — balances and IDs are lost. For anything
you care about keeping, add a real database before relying on it.

---

## Option B — Permanent Cloudflare tunnel (laptop must stay on)

Free, keeps data on your machine, gives a URL that does not change.

```bash
cd "C:\Axzora\work on android customized os\tools" && ./cloudflared.exe tunnel login
```

Then:

```bash
cd "C:\Axzora\work on android customized os\tools" && ./cloudflared.exe tunnel create axzora
```

Route it to a hostname you own in Cloudflare and run:

```bash
cd "C:\Axzora\work on android customized os\tools" && ./cloudflared.exe tunnel run --url http://localhost:8787 axzora
```

---

## Current quick tunnel (temporary)

Start the server and tunnel together:

```bash
cd "C:\Axzora\work on android customized os\AxzoraOS\server" && node server.js
```

```bash
cd "C:\Axzora\work on android customized os\tools" && ./cloudflared.exe tunnel --url http://localhost:8787
```

The tunnel prints a `https://<random>.trycloudflare.com` URL. If it differs from
the one in `Config.kt`, update the list and rebuild.

---

## Local network only

If every phone is on the same Wi-Fi as the laptop, no tunnel is needed — but
Windows Firewall blocks the port by default. Run **as administrator**:

```bash
powershell -ExecutionPolicy Bypass -File "C:\Axzora\work on android customized os\AxzoraOS\server\allow-firewall.ps1"
```

Also set the Wi-Fi network to **Private** in Windows settings; on a Public
profile the firewall stays closed even with the rule.
