package eu.mihosoft.vmf.conformance.genericvalues.vmfmodel;

interface ReviewHeader { String getId(); }

interface CollectionReview extends ReviewHeader, Forwarded<Tag> {}
