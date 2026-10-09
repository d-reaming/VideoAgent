/**
 * 前端 API 封装（同源，Spring Boot 8080）。
 * 自动携带 JWT（localStorage）到每个请求；401 时跳转登录页。
 */
const TOKEN_KEY = 'vagent:token';
const USER_KEY = 'vagent:user';

const api = {
    getToken() { return localStorage.getItem(TOKEN_KEY) || ''; },
    getUser() {
        try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); }
        catch (e) { return null; }
    },
    setAuth(token, user) {
        localStorage.setItem(TOKEN_KEY, token);
        localStorage.setItem(USER_KEY, JSON.stringify(user || {}));
    },
    logout() {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        location.href = '/login.html';
    },

    async register(username, password) {
        const r = await fetch('/api/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password }),
        });
        return handle(r);
    },
    async login(username, password) {
        const r = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password }),
        });
        return handle(r);
    },

    async upload(file, title) {
        const fd = new FormData();
        fd.append('file', file);
        if (title) fd.append('title', title);
        const r = await fetch('/api/videos/upload', { method: 'POST', body: fd, headers: authHeader() });
        return handle(r);
    },
    async listVideos() {
        const r = await fetch('/api/videos', { headers: authHeader() });
        return handle(r);
    },
    async getVideo(id) {
        const r = await fetch('/api/videos/' + id, { headers: authHeader() });
        return handle(r);
    },
    async getTask(videoId) {
        const r = await fetch('/api/tasks/video/' + videoId, { headers: authHeader() });
        return handle(r);
    },
    async retry(videoId) {
        const r = await fetch('/api/tasks/video/' + videoId + '/retry', { method: 'POST', headers: authHeader() });
        return handle(r);
    },
    async deleteVideo(id) {
        const r = await fetch('/api/videos/' + id, { method: 'DELETE', headers: authHeader() });
        return handle(r);
    },
    async renameVideo(id, title) {
        const r = await fetch('/api/videos/' + id + '/title', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', ...authHeader() },
            body: JSON.stringify({ title }),
        });
        return handle(r);
    },
    async ask(question, videoId, sessionId) {
        const r = await fetch('/api/qa', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...authHeader() },
            body: JSON.stringify({ question, videoId, sessionId }),
        });
        return handle(r);
    },
    async messages(sessionId) {
        const r = await fetch('/api/sessions/' + sessionId + '/messages', { headers: authHeader() });
        return handle(r);
    },
    async sessionsList(videoId) {
        const r = await fetch('/api/sessions?videoId=' + videoId, { headers: authHeader() });
        return handle(r);
    },
};

function authHeader() {
    const t = api.getToken();
    return t ? { 'Authorization': 'Bearer ' + t } : {};
}

async function handle(r) {
    if (r.status === 401) {
        // 登录失效：清 token 并跳登录页（登录接口本身的 401 除外）
        api.logout();
        throw new Error('未登录或登录已过期');
    }
    let body;
    try {
        body = await r.json();
    } catch (e) {
        throw new Error('服务器响应异常 (' + r.status + ')');
    }
    if (body.code !== 0) {
        throw new Error(body.message || '请求失败');
    }
    return body.data;
}

/** 秒 → mm:ss */
function fmtTime(sec) {
    sec = Math.max(0, Math.round(sec));
    const m = Math.floor(sec / 60), s = sec % 60;
    return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
}

/** 将回答文本中的 [mm:ss] 渲染为可点击跳转链接 */
function renderTimestamps(text, onClick) {
    return text.replace(/\[(\d{1,2}):(\d{2})(?:-(\d{1,2}):(\d{2}))?\]/g, (match, m1, s1, m2, s2) => {
        const sec = parseInt(m1) * 60 + parseInt(s1);
        return `<a class="ts" href="javascript:void(0)" data-sec="${sec}" onclick="(function(e){e.preventDefault();${onClick}(${sec});})(event)">[${match.slice(1, -1)}]</a>`;
    });
}