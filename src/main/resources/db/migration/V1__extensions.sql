-- btree_gist lets an exclusion constraint combine "=" on plain columns with "&&" on time ranges.
-- Used later to forbid two published flash sale slots from overlapping.
CREATE EXTENSION IF NOT EXISTS btree_gist;
