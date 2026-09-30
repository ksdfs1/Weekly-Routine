// Weekly Routine — tiny storage API backing the GitHub Pages front-end.
// GET  /state  -> returns the last saved routine JSON (public read)
// POST /state  -> overwrites it (requires "Authorization: Bearer <WRITE_TOKEN>")

function corsHeaders(origin) {
  return {
    "Access-Control-Allow-Origin": origin || "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "86400",
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const origin = request.headers.get("Origin") || "*";
    const headers = corsHeaders(origin);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers });
    }

    if (url.pathname === "/state" && request.method === "GET") {
      const data = await env.ROUTINE_KV.get("state");
      return new Response(data || "null", {
        status: 200,
        headers: { ...headers, "Content-Type": "application/json" },
      });
    }

    if (url.pathname === "/state" && request.method === "POST") {
      const auth = request.headers.get("Authorization") || "";
      const token = auth.replace(/^Bearer\s+/i, "");
      if (!env.WRITE_TOKEN || token !== env.WRITE_TOKEN) {
        return new Response(JSON.stringify({ error: "unauthorized" }), {
          status: 401,
          headers: { ...headers, "Content-Type": "application/json" },
        });
      }
      const body = await request.text();
      if (body.length > 300000) {
        return new Response(JSON.stringify({ error: "too_large" }), {
          status: 413,
          headers,
        });
      }
      try {
        JSON.parse(body);
      } catch (e) {
        return new Response(JSON.stringify({ error: "invalid_json" }), {
          status: 400,
          headers: { ...headers, "Content-Type": "application/json" },
        });
      }
      await env.ROUTINE_KV.put("state", body);
      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { ...headers, "Content-Type": "application/json" },
      });
    }

    return new Response("Not found", { status: 404, headers });
  },
};
