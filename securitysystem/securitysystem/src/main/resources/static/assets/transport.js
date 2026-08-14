(() => {
  const enc = new TextEncoder()
  const dec = new TextDecoder()

  function b64ToBuf(b64) {
    const bin = atob(b64)
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    return bytes.buffer
  }

  function bufToB64(buf) {
    const bytes = new Uint8Array(buf)
    let bin = ""
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i])
    return btoa(bin)
  }

  function aadFor(method, path) {
    const p = (path || "").split("?")[0]
    const uri = p.startsWith("http") ? (new URL(p)).pathname : p
    return enc.encode((method || "GET").toUpperCase() + " " + uri)
  }

  async function genRsa() {
    return await crypto.subtle.generateKey(
      { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
      true,
      ["encrypt", "decrypt"]
    )
  }

  const state = {
    protocol: "tls",
    suite: "TLS_AES_256_GCM_SHA256",
    serverPublicKey: null
  }

  function withTimeout(promise, ms, code) {
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => reject(new Error(code || "timeout")), ms)
      promise.then(
        value => {
          window.clearTimeout(timer)
          resolve(value)
        },
        err => {
          window.clearTimeout(timer)
          reject(err)
        }
      )
    })
  }

  function resetSession() {
    state.serverPublicKey = null
  }

  function shouldRetryTransport(rawMessage) {
    const msg = (rawMessage || "").toString().trim()
    return msg === "request_failed"
      || msg === "transport_wrapped_key_required"
      || msg === "transport_session_invalid"
      || msg === "unsupported_transport"
  }

  async function ensureSession() {
    if (!window.crypto || !window.crypto.subtle) return
    if (state.serverPublicKey) return
    const handshake = await withTimeout(apiFetch("/api/transport/handshake", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ protocol: state.protocol })
    }), 8000, "transport_handshake_timeout")
    state.serverPublicKey = await crypto.subtle.importKey(
      "spki",
      b64ToBuf(handshake.serverPublicKey),
      { name: "RSA-OAEP", hash: "SHA-256" },
      false,
      ["encrypt"]
    )
  }

  async function encryptBody(method, path, bodyStr, aesKey) {
    const iv = crypto.getRandomValues(new Uint8Array(12))
    const aad = aadFor(method, path)
    const pt = enc.encode(bodyStr || "")
    const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: aad }, aesKey, pt)
    return { iv: bufToB64(iv.buffer), ciphertext: bufToB64(ct) }
  }

  async function decryptBody(method, path, envelope, aesKey) {
    const iv = new Uint8Array(b64ToBuf(envelope.iv))
    const ct = b64ToBuf(envelope.ciphertext)
    const aad = aadFor(method, path)
    const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv, additionalData: aad }, aesKey, ct)
    return dec.decode(pt || new ArrayBuffer(0))
  }

  async function fetchEncrypted(path, opts, allowRetry = true) {
    const requestedMethod = (opts && opts.method ? opts.method : "GET").toUpperCase()
    if (!["GET", "HEAD", "OPTIONS", "TRACE"].includes(requestedMethod)) {
      if (!window.__csrf || !window.__csrf.token) await initCsrf()
    }
    if (!window.crypto || !window.crypto.subtle) {
      const method = requestedMethod
      const headers = Object.assign({ "Accept": "application/json" }, (opts && opts.headers) ? opts.headers : {})
      if (window.__csrf && window.__csrf.token && !["GET", "HEAD", "OPTIONS", "TRACE"].includes(method)) {
        headers[window.__csrf.headerName || "X-XSRF-TOKEN"] = window.__csrf.token
      }
      let body = opts && opts.body ? opts.body : null
      if (body != null && typeof body !== "string") body = JSON.stringify(body)
      const init = Object.assign({}, opts || {}, { credentials: "include", method, headers })
      if (body != null) {
        headers["Content-Type"] = "application/json"
        init.body = body
      }
      const res = await fetch(path, init)
      const ct = (res.headers.get("content-type") || "").toLowerCase()
      let data = null
      if (ct.includes("application/json")) {
        data = await res.json().catch(() => null)
      } else {
        data = await res.text().catch(() => "")
      }
      if (!res.ok) {
        const msg = data && data.message ? data.message : (typeof data === "string" ? data : "request_failed")
        throw new Error(mapErrorMessage(msg))
      }
      return data
    }

    await ensureSession()
    const method = requestedMethod
    const headers = Object.assign({ "Accept": "application/json" }, (opts && opts.headers) ? opts.headers : {})
    if (window.__csrf && window.__csrf.token && !["GET", "HEAD", "OPTIONS", "TRACE"].includes(method)) {
      headers[window.__csrf.headerName || "X-XSRF-TOKEN"] = window.__csrf.token
    }
    headers["X-QAR-Encrypted"] = "1"
    headers["X-QAR-Transport"] = state.protocol

    const aesKey = await crypto.subtle.generateKey(
      { name: "AES-GCM", length: 256 },
      true,
      ["encrypt", "decrypt"]
    )
    const rawAes = await crypto.subtle.exportKey("raw", aesKey)
    const wrappedKey = await crypto.subtle.encrypt({ name: "RSA-OAEP" }, state.serverPublicKey, rawAes)
    headers["X-QAR-Wrapped-Key"] = bufToB64(wrappedKey)

    let body = opts && opts.body ? opts.body : null
    let init = Object.assign({}, opts || {}, { credentials: "include", method, headers })

    if (body != null && typeof body !== "string") body = JSON.stringify(body)
    if (body != null) {
      headers["Content-Type"] = "application/json"
      const env = await encryptBody(method, path, body, aesKey)
      init.body = JSON.stringify(env)
    } else {
      delete init.body
    }

    try {
      const res = await fetch(path, init)
      const ct = (res.headers.get("content-type") || "").toLowerCase()
      let data = null
      if (ct.includes("application/json")) {
        const env = await res.json().catch(() => null)
        if (env && env.iv && env.ciphertext) {
          const plain = await decryptBody(method, path, env, aesKey)
          data = plain ? JSON.parse(plain) : null
        } else {
          data = env
        }
      } else {
        const txtEnv = await res.text().catch(() => "")
        data = txtEnv
      }

      if (!res.ok) {
        const msg = data && data.message ? data.message : (typeof data === "string" ? data : "request_failed")
        if (allowRetry && shouldRetryTransport(msg)) {
          resetSession()
          return await fetchEncrypted(path, opts, false)
        }
        throw new Error(mapErrorMessage(msg))
      }
      return data
    } catch (e) {
      if (allowRetry && shouldRetryTransport(e && e.message)) {
        resetSession()
        return await fetchEncrypted(path, opts, false)
      }
      throw e
    }
  }

  window.TransportCrypto = {
    ensureSession,
    fetch: fetchEncrypted
  }
})()
