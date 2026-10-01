-- Database entities use the same snapshot keys, evidence locations and immutable-row guards.
ALTER TABLE java_symbol DROP CONSTRAINT java_symbol_kind_check;
ALTER TABLE java_symbol ADD CONSTRAINT java_symbol_kind_check CHECK
    (kind IN ('PACKAGE','CLASS','INTERFACE','METHOD','FIELD','CONTEXT','BEAN','ROUTE','FORM','FORWARD','VIEW','SERVLET',
              'DATABASE_TABLE','QUERY_ARTIFACT'));
ALTER TABLE java_relationship DROP CONSTRAINT java_relationship_relationship_type_check;
ALTER TABLE java_relationship ADD CONSTRAINT java_relationship_relationship_type_check CHECK
    (relationship_type IN ('CONTAINS','IMPORTS','EXTENDS','IMPLEMENTS','OVERRIDES','CALLS',
                           'ROUTES_TO','FORWARDS_TO','RENDERS','INJECTS','WIRES_TO',
                           'READS_TABLE','WRITES_TABLE','MAPS_TO_TABLE','DECLARES_QUERY','EXECUTES_QUERY'));
