INSERT INTO transaction_statuses (code, name, description, is_final) VALUES
('PENDING',    'Pending',    'Transaction created and waiting to be sent to provider', 0),
('PROCESSING', 'Processing', 'Transaction sent to provider and awaiting confirmation',  0),
('APPROVED',   'Approved',   'Transaction successfully processed by provider',          1),
('REJECTED',   'Rejected',   'Transaction rejected by provider',                        1),
('EXPIRED',    'Expired',    'Payment link expired before completion',                  1),
('CANCELLED',  'Cancelled',  'Transaction cancelled by client or system',               1),
('ERROR',      'Error',      'Unexpected error during processing',                      1);

INSERT INTO document_types (code, name, description, country, active) VALUES
('CC',   'Cédula de Ciudadanía',          'Colombian national ID card',              'CO', 1),
('CE',   'Cédula de Extranjería',         'Foreign resident ID in Colombia',         'CO', 1),
('NIT',  'Número de Identificación Tributaria', 'Colombian tax ID for companies',   'CO', 1),
('PP',   'Pasaporte',                     'International passport',                  'CO', 1),
('TI',   'Tarjeta de Identidad',          'Colombian minor identity card',           'CO', 1),
('RUT',  'Registro Único Tributario',     'Colombian tax registration',              'CO', 1),
('DNI',  'Documento Nacional de Identidad','National ID (various countries)',        'CO', 1),
('NUIP', 'Número Único de Identificación Personal', 'Colombian unique personal ID', 'CO', 1);

INSERT INTO payment_methods (id, name, provider, active) VALUES
('PSE',       'PSE - Débito en línea',          'MOCK', 1),
('CARD_VISA', 'Tarjeta de Crédito Visa',        'MOCK', 1),
('CARD_MC',   'Tarjeta de Crédito Mastercard',  'MOCK', 1),
('NEQUI',     'Nequi - Billetera Digital',      'MOCK', 1),
('DAVIPLATA', 'Daviplata - Billetera Digital',  'MOCK', 1),
('EFECTY',    'Efecty - Pago en efectivo',      'MOCK', 1),
('BRE_B',     'Bre-B - Pagos Inmediatos',       'MOCK', 1);
