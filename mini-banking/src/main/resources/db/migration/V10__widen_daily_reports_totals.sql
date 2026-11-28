ALTER TABLE daily_reports
    ALTER COLUMN total_deposit TYPE NUMERIC(38, 0),
    ALTER COLUMN total_withdrawal TYPE NUMERIC(38, 0),
    ALTER COLUMN total_transfer TYPE NUMERIC(38, 0),
    ALTER COLUMN total_refund TYPE NUMERIC(38, 0);
