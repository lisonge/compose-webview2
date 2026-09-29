// Application-owned request IDs, Promise wrapping, timeouts and errors.
(() => {
    let sequence = 0;
    const pending = new Map();
    NativeMessages.onmessage = ({data:r}) => {
        const task = pending.get(r.id);
        if (!task) return;
        pending.delete(r.id); clearTimeout(task.timer);
        if (r.ok) task.resolve(r.value);
        else { const error = new Error(r.error.message); error.name = r.error.name; task.reject(error); }
    };
    const request = (method, args) => new Promise((resolve, reject) => {
        if (pending.size >= 256) { reject(new Error('Too many pending requests')); return; }
        const id = ++sequence;
        const timer = setTimeout(() => { pending.delete(id); reject(new Error('Request timed out')); }, 35000);
        pending.set(id, {resolve, reject, timer});
        try { NativeMessages.postMessage({id, method, args}); }
        catch(e) { pending.delete(id); clearTimeout(timer); reject(e); }
    });
    addEventListener('pagehide', () => {
        for (const task of pending.values()) { clearTimeout(task.timer); task.reject(new Error('Page closed')); }
        pending.clear();
    });
    window.App = Object.freeze({
        getVersion: () => NativeApp.getVersion(),
        greet: name => request('greet', [name]),
        fetchGet: (url, options) => request('fetchGet', [url, options])
    });
})();
