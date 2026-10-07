import psutil, json
p = psutil.Process(15744)
env = p.environ()
keys = [k for k in env if 'CACHE' in k or 'HONGGUO' in k]
print("CACHE/HONGGUO env keys:")
for k in sorted(keys):
    print("  %s=%r" % (k, env[k]))
print("MAX_BYTES raw:", env.get('HONGGUO_CACHE_MAX_BYTES', '<MISSING>'))
print("KEEP_FILES raw:", env.get('HONGGUO_CACHE_KEEP_FILES', '<MISSING>'))
conns = [c for c in p.net_connections(kind='inet') if c.status == psutil.CONN_LISTEN]
print("LISTEN:", [(c.laddr.port) for c in conns])
print("API_KEY present:", 'HONGGUO_SESSION_API_KEY' in env)
print("WORK_DIR:", env.get('HONGGUO_HLS_WORK_DIR'))
print("DATA_DIR:", env.get('HONGGUO_BACKEND_DATA_DIR'))
