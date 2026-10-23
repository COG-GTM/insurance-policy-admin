-- AB-521: DB2 POLICY_SEQ from sql/ddl/create-tables.sql lines 182-190.
-- INTEGER NO MAXVALUE caps at 2147483647; NO CYCLE makes nextval fail at max as DB2 SQLSTATE 23522.
-- CACHE 50 is preserved; PostgreSQL per-session allocation may leave gaps on restart, as DB2 does.
CREATE SEQUENCE pas.policy_seq AS integer START WITH 1000000 INCREMENT BY 1
    NO MAXVALUE NO CYCLE CACHE 50;
