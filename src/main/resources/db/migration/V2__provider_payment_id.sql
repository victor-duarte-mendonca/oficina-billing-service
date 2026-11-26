-- ADR 0023: o provedor passou a ser a Stripe; o identificador deixa de ser específico do Mercado Pago.
ALTER TABLE payment RENAME COLUMN mp_payment_id TO provider_payment_id;
