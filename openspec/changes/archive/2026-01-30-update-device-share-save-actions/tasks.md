## 1. Implementation
- [x] 1.1 Add distinct request actions for device-share Save vs View and update UI bindings.
- [x] 1.2 Use cached save path for Save without enabling auto-approve; keep View approval non-persistent.
- [x] 1.3 Update notification detail text to describe Save and View behaviors and show save path.
- [x] 1.4 Disconnect auto-receive sessions after completion or error.
- [x] 1.5 Create notifications for Save/Auto-Save completion or failure.
- [x] 1.6 Create notifications for View connection success or failure.
- [x] 1.7 Add or update tests where applicable.

## 2. Validation
- [x] 2.1 Manual: trigger a device-share request, tap Save, verify cached/default save path is used and future requests still prompt.
- [x] 2.2 Manual: tap View, verify no auto-save occurs and auto-approve remains disabled.
- [x] 2.3 Manual: verify Save/Auto-Save disconnects after transfers finish or fail.
- [x] 2.4 Manual: verify Save/Auto-Save completion or failure creates a notification with save path.
- [x] 2.5 Manual: verify View connection success/failure creates a notification.
