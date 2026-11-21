/**
 * The address of the cashier own cashbox. It lives in `shared` because the cashbox owns the screen and the payments offer a way to it
 * when cash is charged with no open session, and a feature never imports another.
 */
export const MY_CASHBOX_PATH = "/cashbox";
