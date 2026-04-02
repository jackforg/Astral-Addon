import { createServer } from "node:http";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const port = Number.parseInt(process.env.PORT ?? "8787", 10);
const dataFile = process.env.ASTRAL_GLOW_DATA_FILE ?? join(__dirname, "glow-registry-data.json");
const publicListUrl = process.env.ASTRAL_GLOW_PUBLIC_LIST_URL ?? "";
const githubRepo = process.env.ASTRAL_GLOW_GITHUB_REPO ?? "";
const githubBranch = process.env.ASTRAL_GLOW_GITHUB_BRANCH ?? "main";
const githubPath = process.env.ASTRAL_GLOW_GITHUB_PATH ?? "glow_list.json";
const githubToken = process.env.ASTRAL_GLOW_GITHUB_TOKEN ?? "";

const corsHeaders = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type"
};

function json(status, body) {
    return {
        status,
        headers: {
            ...corsHeaders,
            "Content-Type": "application/json; charset=utf-8"
        },
        body: JSON.stringify(body, null, 2)
    };
}

function nowIso() {
    return new Date().toISOString();
}

function defaultRegistry() {
    return {
        notice: "Public AstralGlow registry. Entries are opt-in, user-submitted, and used only for client-side glow cosmetics.",
        users: [],
        updatedAt: nowIso()
    };
}

async function loadRegistry() {
    try {
        const raw = await readFile(dataFile, "utf8");
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== "object") return defaultRegistry();
        if (!Array.isArray(parsed.users)) parsed.users = [];
        if (typeof parsed.notice !== "string") parsed.notice = defaultRegistry().notice;
        if (typeof parsed.updatedAt !== "string") parsed.updatedAt = nowIso();
        return parsed;
    } catch (error) {
        if (error && error.code === "ENOENT") return defaultRegistry();
        throw error;
    }
}

async function saveRegistry(registry) {
    await mkdir(dirname(dataFile), { recursive: true });
    await writeFile(dataFile, JSON.stringify(registry, null, 2), "utf8");
}

function compactRegistry(registry) {
    return registry.users.map(user => user.uuid);
}

function validateUuid(value) {
    return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function validateUsername(value) {
    return typeof value === "string" && /^[A-Za-z0-9_]{1,16}$/.test(value);
}

async function readJsonBody(request) {
    const chunks = [];
    for await (const chunk of request) {
        chunks.push(chunk);
        if (Buffer.concat(chunks).length > 16_384) throw new Error("Request body too large.");
    }

    const raw = Buffer.concat(chunks).toString("utf8");
    return raw ? JSON.parse(raw) : {};
}

function upsertUser(registry, payload) {
    const existing = registry.users.find(user => user.uuid === payload.uuid);
    const timestamp = nowIso();

    if (existing) {
        existing.username = payload.username;
        existing.lastSeenAt = timestamp;
        existing.source = "share-presence";
        return { added: false, user: existing };
    }

    const user = {
        uuid: payload.uuid,
        username: payload.username,
        source: "share-presence",
        optedInAt: timestamp,
        lastSeenAt: timestamp
    };

    registry.users.push(user);
    registry.users.sort((a, b) => a.username.localeCompare(b.username, undefined, { sensitivity: "base" }));
    return { added: true, user };
}

async function mirrorToGitHub(registry) {
    if (!githubRepo || !githubToken) {
        return { githubMirrored: null, message: "GitHub mirroring is not configured on this registry service." };
    }

    const contentUrl = `https://api.github.com/repos/${githubRepo}/contents/${githubPath}?ref=${encodeURIComponent(githubBranch)}`;
    const headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": `Bearer ${githubToken}`,
        "User-Agent": "astral-glow-registry"
    };

    let sha;
    const current = await fetch(contentUrl, { headers });
    if (current.ok) {
        const currentJson = await current.json();
        sha = currentJson.sha;
    } else if (current.status !== 404) {
        throw new Error(`GitHub contents lookup failed with HTTP ${current.status}.`);
    }

    const body = {
        message: `Update Astral glow registry (${registry.users.length} users)`,
        content: Buffer.from(JSON.stringify(registry, null, 2), "utf8").toString("base64"),
        branch: githubBranch
    };

    if (sha) body.sha = sha;

    const update = await fetch(`https://api.github.com/repos/${githubRepo}/contents/${githubPath}`, {
        method: "PUT",
        headers: {
            ...headers,
            "Content-Type": "application/json"
        },
        body: JSON.stringify(body)
    });

    if (!update.ok) {
        const text = await update.text();
        throw new Error(`GitHub mirror update failed with HTTP ${update.status}: ${text}`);
    }

    return {
        githubMirrored: true,
        message: `GitHub mirror updated at ${githubRepo}/${githubPath}.`
    };
}

async function handleGetList(compact = false) {
    const registry = await loadRegistry();
    return json(200, compact ? compactRegistry(registry) : registry);
}

async function handleShare(request) {
    const payload = await readJsonBody(request);
    if (!validateUuid(payload.uuid)) {
        return json(400, { ok: false, message: "Expected a valid UUID string." });
    }

    if (!validateUsername(payload.username)) {
        return json(400, { ok: false, message: "Expected a valid Minecraft username." });
    }

    const registry = await loadRegistry();
    const result = upsertUser(registry, payload);
    registry.updatedAt = nowIso();
    await saveRegistry(registry);

    let mirror = {
        githubMirrored: null,
        message: "GitHub mirroring is not configured on this registry service."
    };

    try {
        mirror = await mirrorToGitHub(registry);
    } catch (error) {
        mirror = {
            githubMirrored: false,
            message: error instanceof Error ? error.message : String(error)
        };
    }

    return json(200, {
        ok: true,
        added: result.added,
        sharedFields: ["uuid", "username"],
        publicListUrl,
        githubMirrored: mirror.githubMirrored,
        userCount: registry.users.length,
        message: `${result.added ? "Presence stored" : "Presence refreshed"} and public registry updated. ${mirror.message}`.trim()
    });
}

const server = createServer(async (request, response) => {
    try {
        if (!request.url) {
            const result = json(400, { ok: false, message: "Missing request URL." });
            response.writeHead(result.status, result.headers);
            response.end(result.body);
            return;
        }

        const url = new URL(request.url, `http://${request.headers.host ?? "localhost"}`);

        if (request.method === "OPTIONS") {
            response.writeHead(204, corsHeaders);
            response.end();
            return;
        }

        if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
            const result = json(200, {
                ok: true,
                service: "astral-glow-registry",
                publicListUrl,
                githubMirrorConfigured: Boolean(githubRepo && githubToken)
            });
            response.writeHead(result.status, result.headers);
            response.end(result.body);
            return;
        }

        if (request.method === "GET" && (url.pathname === "/glow_list.json" || url.pathname === "/glow-list")) {
            const result = await handleGetList(false);
            response.writeHead(result.status, result.headers);
            response.end(result.body);
            return;
        }

        if (request.method === "GET" && url.pathname === "/glow-list/compact") {
            const result = await handleGetList(true);
            response.writeHead(result.status, result.headers);
            response.end(result.body);
            return;
        }

        if (request.method === "POST" && url.pathname === "/share") {
            const result = await handleShare(request);
            response.writeHead(result.status, result.headers);
            response.end(result.body);
            return;
        }

        const result = json(404, { ok: false, message: "Not found." });
        response.writeHead(result.status, result.headers);
        response.end(result.body);
    } catch (error) {
        const result = json(500, {
            ok: false,
            message: error instanceof Error ? error.message : String(error)
        });
        response.writeHead(result.status, result.headers);
        response.end(result.body);
    }
});

server.listen(port, () => {
    console.log(`Astral glow registry listening on http://localhost:${port}`);
});
