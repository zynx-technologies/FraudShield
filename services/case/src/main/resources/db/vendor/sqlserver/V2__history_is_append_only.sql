CREATE TRIGGER trg_case_event_append_only ON case_event
INSTEAD OF UPDATE, DELETE
AS
BEGIN
    THROW 51000, 'case_event is append-only', 1;
END;
GO

CREATE TRIGGER trg_outcome_append_only ON outcome
INSTEAD OF UPDATE, DELETE
AS
BEGIN
    THROW 51001, 'outcome is append-only; record a correction as a new row', 1;
END;