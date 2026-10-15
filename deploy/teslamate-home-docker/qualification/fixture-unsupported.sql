-- Intentionally incompatible session schema; disposable compat430 DB only.
\set ON_ERROR_STOP on
BEGIN;
CREATE SCHEMA compat_unsupported;
CREATE TABLE compat_unsupported.drives AS TABLE public.drives;
CREATE TABLE compat_unsupported.positions AS TABLE public.positions;
CREATE TABLE compat_unsupported.addresses AS TABLE public.addresses;
CREATE TABLE compat_unsupported.charging_processes AS TABLE public.charging_processes;
ALTER TABLE compat_unsupported.charging_processes DROP COLUMN charge_energy_added;
COMMIT;
