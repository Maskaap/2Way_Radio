# Database tests (plain PostgreSQL, no Supabase needed)

```bash
createdb ptt_test
psql -d ptt_test -f 00_local_supabase_shim.sql          # fake auth schema + roles like Supabase
psql -d ptt_test -f ../migrations/20261122000001_ptt_connect_init.sql
psql -d ptt_test -f 10_behaviour_test.sql                # floor control, RLS, blocking, private calls
```
Do NOT run the shim against a real Supabase project.
