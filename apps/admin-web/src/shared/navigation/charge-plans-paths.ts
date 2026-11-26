/**
 * The address of the charge plans. It lives in `shared` because the charges own the screen and other areas lead to it (the simulator of charges
 * sends a person with no active plan to create one), and a feature never imports another.
 */
export const CHARGE_PLANS_PATH = "/charges/plans";
