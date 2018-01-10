(ns discljord.messaging)

(defn mention
  [user-id]
  (str "<@" user-id ">"))
