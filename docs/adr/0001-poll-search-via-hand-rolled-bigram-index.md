# Poll search via a hand-rolled bigram index

Poll Search must stay fast at 100k–1M Polls, and most Chinese queries are only two characters long. pg_trgm cannot index patterns shorter than three characters, Neon (our current host) does not offer pg_bigm, zhparser or pg_jieba, and an external engine (Meilisearch) adds a service plus a sync pipeline. We therefore store each Poll's lowercase title+description as a generated `text[]` of distinct character bigrams with a GIN index, find candidates with `@>`, and confirm with `LIKE`. It uses only core Postgres, so it moves with us to any Postgres host.

## Consequences

- One-character queries cannot use the index and fall back to a scan.
- Results are ordered by recency, not relevance. Relevance ranking or typo tolerance means moving Search to an external engine; the API's `q` parameter does not change.
- Verified on Postgres 17 with 1M rows: a two-character query takes 0.2 ms through the index versus 216 ms for an unindexed `ILIKE`. The index cost 476 MB on random text (an upper bound), so watch Neon storage limits.
