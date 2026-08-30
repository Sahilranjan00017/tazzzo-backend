// Tazzzo Implementation Contract V1 — executable attack (validator-level subset)
// Runs against a clean mongod. Prints one line per test: ID PASS/FAIL detail.
const db2 = db.getSiblingDB("tazzzo_test");
db2.dropDatabase();
let results = [];
function expect(id, desc, fn, wantError, reasonToken) {
  try { fn(); results.push([id, wantError ? "FAIL" : "PASS", desc, wantError ? "write was ACCEPTED but must be rejected" : "accepted as expected"]); }
  catch (e) {
    if (!wantError) { results.push([id, "FAIL", desc, "unexpected reject: " + e.message.substring(0,90)]); return; }
    const detail = e.errInfo ? JSON.stringify(e.errInfo.details) : e.message;
    if (reasonToken && detail.indexOf(reasonToken) === -1) {
      results.push([id, "FAIL", desc, "rejected but for the WRONG reason (wanted '"+reasonToken+"')"]);
    } else {
      results.push([id, "PASS", desc, reasonToken ? ("rejected for the right reason: "+reasonToken) : "rejected as expected"]);
    }
  }
}

// ---- create products collection with the REAL contract validator
const productsValidator = { $jsonSchema: { bsonType:"object", additionalProperties:false,
  required:["_id","product_type","identity","brand_code","title","lifecycle","classification","attributes","attributes_meta","version","created_at"],
  properties:{
    _id:{bsonType:"string", pattern:"^TZP-"},
    product_type:{enum:["single","variant_pack","bundle"]},
    lifecycle:{enum:["draft","active","merging","discontinued","archived","merged"]},
    identity:{bsonType:"object", additionalProperties:false, required:["type"],
      properties:{type:{enum:["gtin","internal"]}, internal_key:{bsonType:["string","null"]}}},
    gtins:{bsonType:"array", maxItems:12, items:{bsonType:"object", additionalProperties:false, required:["value"],
      properties:{value:{bsonType:"string"}, market:{bsonType:"string"}, valid_from:{bsonType:"date"}, valid_to:{bsonType:["date","null"]}}}},
    brand_code:{bsonType:"string"},
    title:{bsonType:"string"},
    localized_titles:{bsonType:"object", additionalProperties:false, properties:{ en:{bsonType:"string"}, hi:{bsonType:"string"} }}, // generated lang list: en,hi
    classification:{bsonType:"object", additionalProperties:false, required:["vertical_id","release_id","status"],
      properties:{ vertical_id:{bsonType:["string","null"]}, release_id:{bsonType:"string"},
        status:{enum:["confirmed","provisional","review","scope_blocked"]},
        confidence:{bsonType:["double","null"], minimum:0, maximum:1},
        method_detail:{bsonType:"object"},
        evidence_refs:{bsonType:"array", maxItems:20, items:{bsonType:"string", pattern:"^EV-"}}}},
    attributes:{bsonType:"object"},
    attributes_meta:{bsonType:"object", required:["validated_release"], properties:{validated_release:{bsonType:"string"}}},
    attribute_provenance:{bsonType:"object"},
    bundle_contents:{bsonType:["array","null"], maxItems:100, items:{bsonType:"object", additionalProperties:false,
      required:["component_product_id","qty"], properties:{component_product_id:{bsonType:"string",pattern:"^TZP-"},
      qty:{bsonType:"int",minimum:1}, vertical_id_snapshot:{bsonType:"string"}, title_snapshot:{bsonType:"string"}, gtin_snapshot:{bsonType:["string","null"]}}}},
    browse_verticals:{bsonType:["array","null"], maxItems:120, items:{bsonType:"string"}},
    variant_group_id:{bsonType:["string","null"]},
    formulation_version:{bsonType:["int","null"]},
    ext:{bsonType:"object", additionalProperties:false, properties:{}}, // registry empty at genesis
    merged_into:{bsonType:["string","null"]},
    version:{bsonType:"int", minimum:1},
    created_at:{bsonType:"date"}, updated_at:{bsonType:["date","null"]}},
  oneOf:[
    { properties:{ product_type:{enum:["single","variant_pack"]}, classification:{properties:{vertical_id:{bsonType:"string"}}}, bundle_contents:{bsonType:"null"} } },
    { properties:{ product_type:{enum:["bundle"]}, classification:{properties:{vertical_id:{bsonType:"null"}}}, bundle_contents:{bsonType:"array", minItems:2} } } ] } };

db2.createCollection("products", {validator: productsValidator, validationLevel:"strict", validationAction:"error"});
db2.createCollection("gtin_registry");   // _id = gtin
db2.createCollection("identity_keys");   // _id = key
db2.createCollection("brands");
db2.products.createIndex({"classification.vertical_id":1, lifecycle:1, "classification.status":1});
db2.products.createIndex({"bundle_contents.component_product_id":1}, {sparse:true});

const schemaBefore = JSON.stringify(db2.getCollectionInfos().map(c=>({n:c.name,o:c.options}))) ;
const indexesBefore = JSON.stringify(db2.products.getIndexes());

function validSingle(id, extra) { return Object.assign({
  _id:id, product_type:"single", identity:{type:"gtin"}, brand_code:"BR-NEWBRAND", title:"Test", lifecycle:"draft",
  classification:{vertical_id:"TZV-000123", release_id:"1.0.0", status:"provisional", method_detail:{}, evidence_refs:["EV-000001"]},
  attributes:{}, attributes_meta:{validated_release:"1.0.0"}, version:NumberInt(1), created_at:new Date()}, extra||{}); }

// ===== AT-1: SKU #100001 — new brand, unseen attribute combo, 2 GTINs, evidence
expect("AT1-a","brands: new brand = plain insert", ()=>db2.brands.insertOne({_id:"BR-NEWBRAND", canonical_name:"NewBrand"}), false);
expect("AT1-b","gtin_registry: two concurrent bindings = inserts", ()=>{ db2.gtin_registry.insertOne({_id:"8901111111111", bindings:[{product_id:"TZP-100001", from:new Date(), to:null, market:"IN"}]}); db2.gtin_registry.insertOne({_id:"0071111111111", bindings:[{product_id:"TZP-100001", from:new Date(), to:null, market:"US"}]}); }, false);
expect("AT1-c","products: SKU #100001 with UNSEEN attribute keys (protein_g, low_gi) inserts clean", ()=>db2.products.insertOne(validSingle("TZP-100001",{attributes:{protein_g:24, low_gi:true, pack_size:5, pack_unit:"kg"}, gtins:[{value:"8901111111111", market:"IN", valid_from:new Date(), valid_to:null},{value:"0071111111111", market:"US", valid_from:new Date(), valid_to:null}]})), false);
// zero schema drift assertion:
const schemaAfter = JSON.stringify(db2.getCollectionInfos().map(c=>({n:c.name,o:c.options})));
const indexesAfter = JSON.stringify(db2.products.getIndexes());
results.push(["AT1-d", (schemaBefore===schemaAfter && indexesBefore===indexesAfter) ? "PASS":"FAIL", "zero collection/validator/index modification after AT-1", ""]);

// ===== Growth battery (validator level)
expect("G-6","new OPEN attribute value (spice_level='extreme') = data only", ()=>db2.products.insertOne(validSingle("TZP-100002",{attributes:{spice_level:"extreme"}})), false);
expect("G-16","new language KEY (localized_titles.ta) MUST be rejected until pipeline regen", ()=>db2.products.insertOne(validSingle("TZP-100003",{localized_titles:{ta:"தலைப்பு"}})), true, "localized_titles");
expect("G-16b","registered language (hi) accepted", ()=>db2.products.insertOne(validSingle("TZP-100004",{localized_titles:{hi:"शीर्षक"}})), false);

// ===== Invalid-state battery I-1..I-10 (DB-enforceable subset)
expect("I-1","bundle WITH vertical_id rejected (oneOf)", ()=>db2.products.insertOne(Object.assign(validSingle("TZP-200001"),{product_type:"bundle", bundle_contents:[{component_product_id:"TZP-100001",qty:NumberInt(1)},{component_product_id:"TZP-100002",qty:NumberInt(1)}]})), true, "oneOf");
expect("I-2","single with NULL vertical rejected (oneOf)", ()=>{ const d=validSingle("TZP-200002"); d.classification.vertical_id=null; db2.products.insertOne(d); }, true, "oneOf");
expect("I-3","unknown product_type rejected (enum)", ()=>db2.products.insertOne(Object.assign(validSingle("TZP-200003"),{product_type:"subscription"})), true, "product_type");
expect("I-3b","valid bundle (null vertical + 2 contents) ACCEPTED", ()=>{ const d=validSingle("TZP-200004"); d.product_type="bundle"; d.classification.vertical_id=null; d.bundle_contents=[{component_product_id:"TZP-100001",qty:NumberInt(1)},{component_product_id:"TZP-100002",qty:NumberInt(2)}]; db2.products.insertOne(d); }, false);
expect("I-5","duplicate GTIN registry _id rejected (E11000 = collision path)", ()=>db2.gtin_registry.insertOne({_id:"8901111111111", bindings:[]}), true);
expect("I-6","BYPASS TEST (CTO): fake taxonomy key inside attributes is ACCEPTED by Mongo — proving this guarantee is service-layer, as the honest register states", ()=>db2.products.insertOne(validSingle("TZP-200005",{attributes:{fake_taxonomy_category:"whatever"}})), false);
expect("I-7","unregistered ext key rejected (generated additionalProperties)", ()=>db2.products.insertOne(validSingle("TZP-200006",{ext:{rogue_plane:{x:1}}})), true, "ext");
expect("I-9","invalid evidence ref pattern rejected", ()=>{ const d=validSingle("TZP-200007"); d.classification.evidence_refs=["NOT-AN-EV"]; db2.products.insertOne(d); }, true, "evidence_refs");
expect("I-10","bundle with 1 component rejected (minItems)", ()=>{ const d=validSingle("TZP-200008"); d.product_type="bundle"; d.classification.vertical_id=null; d.bundle_contents=[{component_product_id:"TZP-100001",qty:NumberInt(1)}]; db2.products.insertOne(d); }, true);
expect("I-11","undeclared top-level field rejected (additionalProperties:false)", ()=>db2.products.insertOne(validSingle("TZP-200009",{sneaky_new_column:"nope"})), true, "additionalProperties");
expect("I-12","confidence > 1 rejected", ()=>{ const d=validSingle("TZP-200010"); d.classification.confidence=1.5; db2.products.insertOne(d); }, true);
expect("I-13","lifecycle 'merging' is a VALID enum state (M3 fix present)", ()=>db2.products.insertOne(validSingle("TZP-200011",{lifecycle:"merging"})), false);
expect("I-14","gtins beyond maxItems(12) rejected", ()=>{ const g=[]; for(let i=0;i<13;i++) g.push({value:"890"+i, market:"IN", valid_from:new Date(), valid_to:null}); db2.products.insertOne(validSingle("TZP-200012",{gtins:g})); }, true);

// ===== identity mint race (unique _id as gate)
expect("R-1","identity key mint: first insert wins", ()=>db2.identity_keys.insertOne({_id:"BR-X|TZV-1|tomato", product_id:"TZP-300001", status:"active"}), false);
expect("R-2","identity key mint: second insert E11000 -> collision path", ()=>db2.identity_keys.insertOne({_id:"BR-X|TZV-1|tomato", product_id:"TZP-300002", status:"active"}), true);

// ===== update-path checks
expect("U-1","CAS update with correct version succeeds", ()=>{ const r=db2.products.updateOne({_id:"TZP-100001", version:NumberInt(1)},{$set:{title:"Updated"},$inc:{version:NumberInt(1)}}); if(r.modifiedCount!==1) throw new Error("no match"); }, false);
expect("U-2","stale CAS version matches nothing (lost-update impossible)", ()=>{ const r=db2.products.updateOne({_id:"TZP-100001", version:NumberInt(1)},{$set:{title:"Ghost"},$inc:{version:NumberInt(1)}}); if(r.modifiedCount!==1) throw new Error("stale CAS matched 0 docs"); }, true);
expect("U-3","BYPASS TEST: direct product_type mutation is ACCEPTED by Mongo (validator can't enforce immutability) — honest-register item confirmed, auditor required", ()=>{ const r=db2.products.updateOne({_id:"TZP-100002"},{$set:{product_type:"variant_pack"}}); if(r.modifiedCount!==1) throw new Error("no"); }, false);
expect("U-4","but shape rule still binds on update: flipping a single to bundle WITHOUT contents rejected", ()=>{ const r=db2.products.updateOne({_id:"TZP-100004"},{$set:{product_type:"bundle"}}); if(r.modifiedCount!==1) throw new Error("rejected"); }, true);

print("\n===== RESULTS =====");
let fail=0;
results.forEach(r=>{ if(r[1]==="FAIL") fail++; print(r[1].padEnd(5)+" "+r[0].padEnd(6)+" "+r[2]+(r[3]?"  ["+r[3]+"]":"")); });
print("\nTOTAL: "+results.length+" tests, "+fail+" FAIL");
