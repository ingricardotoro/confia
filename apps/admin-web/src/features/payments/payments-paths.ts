/** The path of the payments area. It has the list of payments at its own address and the register of a payment and the detail of one below it. */
export const PAYMENTS_PATH = "/payments";

export const PAYMENTS_PATHS = {
  list: PAYMENTS_PATH,
  register: `${PAYMENTS_PATH}/register`,
} as const;

/** The address of one payment. The id is encoded, so it can never change the path. */
export const paymentPath = (id: string): string => `${PAYMENTS_PATH}/${encodeURIComponent(id)}`;
