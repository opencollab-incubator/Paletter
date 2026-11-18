package org.geysermc.paletter.tools.codegen.component;

import org.geysermc.paletter.tools.codegen.component.ApiModel.Comp;
import org.geysermc.paletter.tools.codegen.component.ApiModel.EnumRef;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Field;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ListOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.MapOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjRef;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjType;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Type;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Vec3;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Object types with the same fields share one record; types with the same name must be the same.
 */
final class TypeMerge {
    private TypeMerge() {
    }

    /** Object types with the same fields share one record; types with the same name must be the same. */
    static void dedupe(ApiModel model, Comp c) {
        Map<String, ObjType> bySignature = new LinkedHashMap<>();
        Map<ObjType, ObjType> replace = new LinkedHashMap<>();
        for (ObjType o : c.allTypes) {
            String signature = signature(o);
            ObjType first = bySignature.putIfAbsent(signature, o);
            if (first != null) {
                replace.put(o, first);
            }
        }
        for (ObjType o : c.allTypes) {
            if (replace.containsKey(o)) {
                continue;
            }
            ObjType other = c.types.put(o.name, o);
            if (other != null) {
                throw new GenerationException("name collision: " + c.id + " has two different types named " + o.name + " ("
                    + other.path + ", " + o.path + "); name one in api.json typeNames");
            }
            if (o.name.equals(c.className) || c.enums.containsKey(o.name) || o.name.equals("Builder") || (c.usesVec3 && o.name.equals("Vec3"))) {
                throw new GenerationException("name collision: " + c.id + " type " + o.name + " (" + o.path + ") clashes with another nested name; name it in api.json typeNames");
            }
        }
        rewrite(c.root, replace);
        for (ObjType o : c.types.values()) {
            rewrite(o, replace);
        }
        for (ApiModel.UnionType u : model.unions.values()) {
            if (u.owner == c) {
                u.variants.forEach(v -> v.type = rewrite(v.type, replace));
                if (u.name.equals(c.className) || c.types.containsKey(u.name) || c.enums.containsKey(u.name)) {
                    throw new GenerationException("name collision: " + c.id + " union " + u.name + " clashes with another nested name; name it in api.json unions");
                }
            }
        }
    }

    static void rewrite(ObjType o, Map<ObjType, ObjType> replace) {
        for (Field f : o.fields) {
            f.type = rewrite(f.type, replace);
        }
    }

    static Type rewrite(Type t, Map<ObjType, ObjType> replace) {
        return switch (t) {
            case ObjRef r -> replace.containsKey(r.type()) ? new ObjRef(replace.get(r.type()), r.wire()) : r;
            case ListOf l -> new ListOf(rewrite(l.element(), replace));
            case MapOf m -> new MapOf(rewrite(m.value(), replace));
            case ApiModel.UnionRef u -> u.object() == null ? u : new ApiModel.UnionRef(u.union(), (ObjRef) rewrite(u.object(), replace));
            default -> t;
        };
    }

    static String signature(ObjType o) {
        StringBuilder s = new StringBuilder("{");
        for (Field f : o.fields) {
            s.append(f.json).append(':').append(typeSignature(f.type)).append('=').append(f.docsDefault).append(f.required)
                .append(f.notBelow == null ? "" : ">=" + f.notBelow.json)
                .append(f.conflictsWith == null ? "" : "!" + f.conflictsWith.json + "=" + f.conflictValue).append(';');
        }
        return s.append('}').toString();
    }

    static String typeSignature(Type t) {
        return switch (t) {
            case ObjRef r -> signature(r.type());
            case ListOf l -> "list<" + typeSignature(l.element()) + ">";
            case MapOf m -> "map<" + typeSignature(m.value()) + ">";
            case EnumRef e -> "enum" + e.type().values;
            default -> t.toString();
        };
    }
}
