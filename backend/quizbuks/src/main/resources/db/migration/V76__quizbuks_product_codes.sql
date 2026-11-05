-- Q100e (docs/quizbuks/plans/Q100-profile-package.md, D-Q100-12): a product's own code, chosen by its sponsor and
-- optional, once per sponsor; profile packages and quiz files find the sponsor's product by it.
ALTER TABLE qb_sponsor_product ADD COLUMN code varchar(40);
CREATE UNIQUE INDEX qb_sponsor_product_code ON qb_sponsor_product (user_id, code) WHERE code IS NOT NULL;
