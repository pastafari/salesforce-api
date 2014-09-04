(ns salesforce-api.core
  ^{:author "Mohit Thatte <mohit@helpshift.com>"
    :doc "This is a thin wrapper around the Salesforce REST API.
          It does not attempt to interpret API responses,
          just gives them back to you to do what you will.
          Note: This library uses password based OAuth,
                so you must be able to keep your secrets!"}
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as cs]))


(def ^:dynamic *version* "31.0")


(defn set-version!
  [v]
  (alter-var-root #'*version* (constantly v)))


(def api-routes
  {:versions "/services/data"
   :limits "/services/data/v%s/limits/"
   :resources "/services/data/v%s/"
   :sobjects "/services/data/v%s/sobjects/"
   :sobjects-meta "/services/data/v%s/sobjects/%s/"
   :sobjects-describe "/services/data/v%s/sobjects/%s/describe/"
   :sobjects-type "/services/data/v%s/sobjects/%s/"
   :sobjects-record "/services/data/v%s/sobjects/%s/%s"
   :sobjects-record-fields "/services/data/v%s/sobjects/%s/%s?fields=%s"
   :sobjects-record-by-extid "/services/data/v%s/sobjects/%s/%s/%s"
   :query "/services/data/v%s/query?q=%s"
   :search "/services/data/v%s/search?q=%s"})


(defn auth!
  "Authenticates against Salesforce auth server.
  Args: config
    :username - your salesforce.com username
    :password - your salesforce.com password
    :security-token - the security token for your salesforce account
    :consumer-key - the consumer key for your connected app
    :consumer-secret - the consumer secret for your connected app
    :sandbox? - (optional) are you connecting to a sandbox? defaults to false
  Returns a map with following keys:
    {:id, :issued_at, :token_type, :instance_url, :signature, :access_token}
  This map needs to be passed into all future calls"
  [{:keys [consumer-key consumer-secret username password security-token sandbox?]
    :or {sandbox? false}}]
  (let [auth-host (if sandbox?
                    "test.salesforce.com"
                    "login.salesforce.com")
        auth-endpoint (format "https://%s/services/oauth2/token" auth-host)
        params {:grant_type "password"
                :client_id consumer-key
                :client_secret consumer-secret
                :username username
                :password (str password security-token)
                :format "json"}
        auth-resp @(http/post auth-endpoint {:form-params params})]
    (json/read-str (:body auth-resp) :key-fn keyword)))


(defn api-call
  "General fn to hit a Salesforce endpoint with auth info"
  [method auth url & {:keys [params] :or {params {}}}]
  (let [endpoint (str (:instance_url auth) url)
        req-params (merge {:oauth-token (:access_token auth)}
                          params)]
    (dissoc @(http/request (merge {:method method :url endpoint} req-params)
                           identity)
            :opts)))


(defn versions
  "Lists available versions of Salesforce API"
  [auth]
  (api-call :get auth (api-routes :versions)))


(defn org-api-limits
  "Lists limits on API usage for the org"
  [auth]
  (api-call :get auth (api-routes :limits)))


(defn resources
  "Lists availables REST resources"
  [auth]
  (api-call :get auth (format (api-routes :resources) *version*)))


(defn list-objects
  "Lists the objects available in your organization and available to the user"
  [auth]
  (let [sobjects-url (format (api-routes :sobjects) *version*)]
    (api-call :get auth sobjects-url)))


(defn get-object-metadata
  "retrieves metadata for an object"
  [auth object]
  (let [sobjects-meta-url (format (api-routes :sobjects-meta) *version* object)]
    (api-call :get auth sobjects-meta-url)))


(defn describe-object
  "retrieve all the metadata for an object, including information about each field, URLs, and child relationships"
  [auth object]
  (let [sobjects-describe-url (format (api-routes :sobjects-describe)
                                      *version*
                                      object)]
    (api-call :get auth sobjects-describe-url)))


(defn create-record
  "create new records of type with attributes"
  [auth type attrs]
  (let [sobjects-create-url (format (api-routes :sobjects-type) *version* type)]
    (api-call :post
              auth
              sobjects-create-url
              :params {:body (json/write-str attrs)
                       :headers {"Content-Type" "application/json"}})))


(defn update-record
  "Updates record with given id, with given attrs"
  [auth type id attrs]
  (let [sobjects-update-url (format (api-routes :sobjects-record)
                                    *version*
                                    type
                                    id)]
    (api-call :patch
              auth
              sobjects-update-url
              :params {:body (json/write-str attrs)
                       :headers {"Content-Type" "application/json"}})))


(defn delete-record
  "deletes record with given id and type"
  [auth type id]
  (let [delete-url (format (api-routes :sobjects-record) *version* type id)]
    (api-call :delete auth delete-url)))


(defn get-record-fields
  "retrieve field values from a record"
  [auth type id fields]
  (let [fields-url (format (api-routes :sobjects-record-fields)
                           *version*
                           type
                           id
                           (cs/join "," fields))]
    (api-call :get auth fields-url)))


(defn get-record-by-external-id
  "retrieve record by external id. must be setup in Salesforce.
   if exactly one record matches, returns the record,
   else returns URL's for all matching records"
  [auth type ext-id-key ext-id-val]
  (let [record-url (format (api-routes :sobjects-record-by-extid)
                           *version*
                           type
                           ext-id-key
                           ext-id-val)]
    (api-call :get auth record-url)))


(defn upsert-record-by-external-id
  "create new records or update existing records (upsert) based on the value of a specified external ID field.
    If the specified value doesn't exist, a new record is created.
    If a record does exist with that value, the field values specified in the request body are updated.
    If the value is not unique, returns a 300 with the list of matching records."
  [auth type ext-id-key ext-id-val attrs]
  (let [upsert-url (format (api-routes :sobjects-record-by-extid)
                           *version*
                           type
                           ext-id-key
                           ext-id-val)]
    (api-call :patch
              auth
              upsert-url
              :params {:body (json/write-str attrs)
                       :headers {"Content-Type" "application/json"}})))

(defn query
  "execute a SOQL query that returns all the results in a single response,
   or if needed, returns part of the results and an identifier used to retrieve
   the remaining results"
  [auth soql-str]
  (let [query-url (format (api-routes :query)
                          *version*
                          (cs/replace soql-str " " "+"))]
    (api-call :get auth query-url)))


(defn search
  "execute a SOSL search. sosl-str must be a valid sosl string.
   remember when using the Force.com API,
   search terms must be enclosed in braces :S"
  [auth sosl-str]
  (let [search-url (format (api-routes :search)
                           *version*
                           (http/url-encode sosl-str))]
    (api-call :get auth search-url)))
