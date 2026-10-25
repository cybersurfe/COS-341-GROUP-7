#!/usr/bin/env python3
# generates the hand-written regression suite. expected outputs are computed by hand / by an independent python
# oracle, never by the compiler itself.   ok/NAME.spl + ok/NAME.out   and   bad/NAME.spl + bad/NAME.err (phase|fragment)
import os, itertools
here = os.path.dirname(os.path.abspath(__file__))

def ok(name, prog, out):
    open(f"{here}/ok/{name}.spl", "w").write(prog.strip() + "\n")
    open(f"{here}/ok/{name}.out", "w").write(out)

def bad(name, prog, phase, frag):
    open(f"{here}/bad/{name}.spl", "w").write(prog.strip() + "\n")
    open(f"{here}/bad/{name}.err", "w").write(f"{phase}|{frag}\n")

# ---------------------------------------------------------------- valid programs
ok("empty_program", ": :", "")
ok("labels_unique_two_calls",   # a function with a loop (=labels) inlined twice, second call uses the first result
   '#a #b : num #sum ( #n ) { #i #s : : #i = 0 ; #s = 0 ; while lesser ( #i #n ) do { #i = add ( #i 1 ) ; #s = add ( #s #i ) ; } ; return ( #s ) } '
   ': #a = #sum ( 4 ) ; #b = #sum ( add ( #a 0 ) ) ; print ( #a ) ; print "," ; print ( #b ) ;',
   "10,55")                                       # sum(4)=10, sum(10)=55
ok("nested_function_uses_outer_variable",
   '#x : num #f ( #p ) { #y : num #g ( #z ) { : : return ( add ( #z #x ) ) } : #y = #g ( #p ) ; return ( mul ( #y 2 ) ) } '
   ': #x = 3 ; print ( #f ( 4 ) ) ;',
   "14")                                          # g(4)=4+3=7, f=14
ok("all_four_loop_forms_iteration_counts",
   '#c : : #c = 0 ; while lesser ( 5 3 ) do { #c = add ( #c 1 ) ; } ; print ( #c ) ; '
   'until larger ( 5 3 ) do { #c = add ( #c 1 ) ; } ; print ( #c ) ; '
   'do { #c = add ( #c 1 ) ; } while lesser ( 5 3 ) ; print ( #c ) ; '
   'do { #c = add ( #c 10 ) ; } until larger ( 5 3 ) ; print ( #c ) ;',
   "00" + "1" + "11")                              # 0 / 0 / 1 / 11 : test-first loops may run 0 times, do-loops once
ok("mod_truncates_toward_zero_like_c",
   ': : print ( mod ( -7 3 ) ) ; print "," ; print ( mod ( 7 -3 ) ) ; print "," ; print ( mod ( -7 -3 ) ) ; print "," ; print ( mod ( 7 3 ) ) ;',
   "-1,1,-1,1")
ok("float_formatting_like_cout",
   ': : print ( div ( 1 4 ) ) ; print "," ; print ( div ( 10 3 ) ) ; print "," ; print ( mul ( 1.5 1.5 ) ) ; print "," ; '
   'print ( sub ( 0.1 0.1 ) ) ; print "," ; print ( mul ( 1000000 10 ) ) ; print "," ; print ( div ( 1 3000000 ) ) ;',
   "0.25,3.33333,2.25,0,1e+07,3.33333e-07")
ok("void_procedure_side_effect_and_by_value",
   '#g : void #inc ( #v ) { : : #v = add ( #v 1 ) ; #g = add ( #g 10 ) ; return } : #g = 1 ; #inc ( #g ) ; print ( #g ) ;',
   "11")                                          # v is a copy, g = 1 + 10
ok("nested_calls_as_arguments_left_to_right",
   ': num #f ( #a #b ) { : : return ( sub ( #a #b ) ) } : print ( #f ( #f ( 10 3 ) #f ( 5 8 ) ) ) ;',
   "10")                                          # f(7,-3) = 10
ok("same_function_name_in_sibling_scopes",
   ': void #a ( ) { : void #h ( ) { : : print "a" ; return } : #h ( ) ; return } '
   'void #b ( ) { : void #h ( ) { : : print "b" ; return } : #h ( ) ; return } : #a ( ) ; #b ( ) ; #a ( ) ;',
   "aba")
ok("variable_and_function_may_share_a_name",
   '#a : num #a ( ) { : : return ( 5 ) } : #a = add ( #a ( ) 1 ) ; print ( #a ) ;',
   "6")
ok("inner_local_shadows_outer_local",
   '#x : void #f ( ) { #x : : #x = 99 ; print ( #x ) ; return } : #x = 1 ; #f ( ) ; print "," ; print ( #x ) ;',
   "99,1")
ok("parameter_shadows_global_and_is_a_copy",
   '#x : void #f ( #x ) { : : #x = 7 ; print ( #x ) ; return } : #x = 1 ; #f ( 5 ) ; print ( #x ) ;',
   "71")
ok("uninitialised_variables_are_zero",
   '#a #b : void #f ( ) { #l : : print ( #l ) ; return } : print ( add ( #a #b ) ) ; #f ( ) ;',
   "00")
ok("string_edge_cases",
   ': : print "" ; print "a-b,c:d.e?f!1" ; print "x" ;',
   "a-b,c:d.e?f!1x")
ok("int_limits_are_accepted",
   ': : print ( 2147483647 ) ; print "," ; print ( -2147483648 ) ; print "," ; print ( 0 ) ;',
   "2147483647,-2147483648,0")
ok("neg_of_zero_float_prints_minus_zero",      # float program (decimal), C++ prints -0
   ': : print ( neg ( 0 ) ) ; print "," ; print ( 0.5 ) ;',
   "-0,0.5")
ok("call_inside_boolean_and_loop_condition",
   '#i : num #sq ( #n ) { : : return ( mul ( #n #n ) ) } : #i = 0 ; while lesser ( #sq ( #i ) 20 ) do { print ( #i ) ; #i = add ( #i 1 ) ; } ;',
   "01234")                                       # 0..4 : 16 < 20, 25 stops
ok("function_called_inside_loop_three_levels",
   '#t #i : num #inner ( #a ) { #k : : #k = 0 ; do { #k = add ( #k #a ) ; } while lesser ( #k 6 ) ; return ( #k ) } '
   '  : #i = 1 ; while lesser ( #i 4 ) do { #t = add ( #t #inner ( #i ) ) ; #i = add ( #i 1 ) ; } ; print ( #t ) ;',
   "18")                                          # inner(1)=6, inner(2)=6, inner(3)=6
ok("four_level_nesting",
   ': num #f1 ( #a ) { : num #f2 ( #b ) { : num #f3 ( #c ) { : num #f4 ( #d ) { : : return ( add ( #d 1 ) ) } : return ( add ( #f4 ( #c ) 10 ) ) } '
   ': return ( add ( #f3 ( #b ) 100 ) ) } : return ( add ( #f2 ( #a ) 1000 ) ) } : print ( #f1 ( 5 ) ) ;',
   "1116")                                        # 5+1 +10 +100 +1000
ok("long_straight_line_program_deep_tree",
   "#a : : " + " ".join(["#a = add ( #a 1 ) ;"] * 6000) + " print ( #a ) ;",
   "6000")
ok("same_function_called_many_times_labels",
   '#r : num #w ( #n ) { #i : : #i = 0 ; until eq ( #i #n ) do { #i = add ( #i 1 ) ; } ; return ( #i ) } : '
   + " ".join(["#r = add ( #r #w ( 3 ) ) ;"] * 12) + ' print ( #r ) ;',
   "36")

# boolean truth tables, oracle = python
T, F = "eq ( 1 1 )", "eq ( 1 2 )"
def ev(e):
    if isinstance(e, bool): return e
    if e[0] == "not": return not ev(e[1])
    if e[0] == "and": return ev(e[1]) and ev(e[2])
    return ev(e[1]) or ev(e[2])
def txt(e):
    if e is True: return T
    if e is False: return F
    return f"{e[0]} ( " + " ".join(txt(x) for x in e[1:]) + " )"
E0 = [True, False]
def grow(es):
    out = list(es)
    out += [("not", a) for a in es]
    out += [(op, a, b) for op in ("and", "or") for a in es for b in es]
    return out
E1 = grow(E0)
E2 = grow(E1)
prog = ": : " + " ".join(f"if {txt(e)} then {{ print \"1\" ; }} else {{ print \"0\" ; }} ;" for e in E2)
ok("boolean_truth_tables_all_depth2", prog, "".join("1" if ev(e) else "0" for e in E2))

# three-valued mix with real comparisons and short circuit through function side effect
ok("short_circuit_or_skips_second_operand",
   '#c : num #bump ( ) { : : #c = add ( #c 1 ) ; return ( #c ) } : if or ( eq ( 1 1 ) eq ( #bump ( ) 5 ) ) then { print "t" ; } else { print "f" ; } ; print ( #c ) ;',
   "t0")                                          # or short-circuits: bump never runs
ok("short_circuit_and_skips_second_operand",
   '#c : num #bump ( ) { : : #c = add ( #c 1 ) ; return ( #c ) } : if and ( eq ( 1 2 ) eq ( #bump ( ) 5 ) ) then { print "t" ; } else { print "f" ; } ; print ( #c ) ;',
   "f0")

# ---------------------------------------------------------------- invalid programs
bad("lexical_leading_zero", ": : #a = 0123 ;", "lexical", "leading zeros")
bad("lexical_bad_name", ": : #A = 1 ;", "lexical", "Invalid token")
bad("syntax_missing_semicolon", "#a : : #a = 1", "syntax", "expected ';'")
bad("syntax_extra_token_at_end", "#a : : #a = 1 ; }", "syntax", "extra token")
bad("sem_undeclared_variable", ": : #x = 1 ;", "semantic", "variable '#x'")
bad("sem_undeclared_in_return_term", ": num #f ( ) { : : return ( #z ) } : nop ;", "semantic", "variable '#z'")
bad("sem_sideways_declaration_not_visible",    # announcement 21: x in g is not visible in h's k
   ": num #f ( ) { : num #g ( ) { #x : : return ( 0 ) } num #h ( ) { : num #k ( ) { #r : : #r = #x ; return ( #r ) } : return ( 0 ) } : return ( 0 ) } : nop ;",
   "semantic", "variable '#x'")
bad("sem_double_declaration_main", "#a #b #a : : nop ;", "semantic", "declared twice")
bad("sem_double_declaration_function_body", ": void #f ( ) { #a #a : : return } : nop ;", "semantic", "declared twice")
bad("sem_local_masks_parameter", ": num #f ( #a ) { #a : : return ( #a ) } : nop ;", "semantic", "masks the parameter")
bad("sem_parameter_listed_twice", ": void #f ( #a #a ) { : : return } : nop ;", "semantic", "listed twice")
bad("sem_function_overloading", ": void #f ( ) { : : return } void #f ( #a ) { : : return } : nop ;", "semantic", "declared twice")
bad("sem_undeclared_function", ": : #nope ( ) ;", "semantic", "function '#nope'")
bad("sem_direct_recursion", ": void #f ( ) { : : #f ( ) ; return } : #f ( ) ;", "semantic", "function '#f'")
bad("sem_indirect_recursion_via_sibling", ": void #f ( ) { : : #g ( ) ; return } void #g ( ) { : : #f ( ) ; return } : nop ;", "semantic", "function '#g'")
bad("sem_main_calls_grandchild", ": void #f ( ) { : void #g ( ) { : : nop ; return } : #g ( ) ; return } : #g ( ) ;", "semantic", "function '#g'")
bad("sem_child_calls_parent_level_function",
    ": void #h ( ) { : : nop ; return } void #f ( ) { : void #g ( ) { : : #h ( ) ; return } : nop ; return } : nop ;", "semantic", "function '#h'")
bad("sem_function_used_as_variable", ": void #f ( ) { : : return } : #f = 1 ;", "semantic", "variable '#f'")
bad("sem_variable_used_as_function", "#v : : #v ( ) ;", "semantic", "function '#v'")
bad("type_mod_and_div_conflict", ": : print ( mod ( 5 2 ) ) ; print ( div ( 5 2 ) ) ;", "type", "float-integer-conflict")
bad("type_mod_and_div_conflict_in_unused_function",
    ": num #f ( ) { : : return ( div ( 1 2 ) ) } : print ( mod ( 5 2 ) ) ;", "type", "float-integer-conflict")
bad("type_mod_with_decimal_number", ": : print ( mod ( 5 2 ) ) ; print ( 0.5 ) ;", "type", "decimal point")
bad("type_num_function_called_as_instruction", ": num #f ( ) { : : return ( 1 ) } : #f ( ) ;", "type", "returns a number")
bad("type_procedure_used_in_term", "#a : void #p ( ) { : : return } : #a = #p ( ) ;", "type", "returns no value")
bad("type_procedure_used_in_print", ": void #p ( ) { : : return } : print ( #p ( ) ) ;", "type", "returns no value")
bad("type_too_few_arguments", ": num #f ( #a #b ) { : : return ( #a ) } : print ( #f ( 1 ) ) ;", "type", "2 parameter(s)")
bad("type_too_many_arguments", ": void #f ( ) { : : return } : #f ( 1 ) ;", "type", "0 parameter(s)")
bad("type_int_literal_too_big", ": : print ( 2147483648 ) ;", "type", "does not fit")
bad("type_int_literal_too_small", ": : print ( -2147483649 ) ;", "type", "does not fit")

# ---------------------------------------------------------------- scale / shape stress (expected values computed here)
def nest_add(n):
    s = "0"
    for _ in range(n): s = f"add ( 1 {s} )"
    return s
ok("stress_term_nesting_1500_deep", f": : print ( {nest_add(1500)} ) ;", "1500")

funcs = " ".join(f"num #f{k} ( ) {{ : : return ( {k} ) }}" for k in range(300))
calls = nest = "0"
for k in range(300): calls = f"add ( {calls} #f{k} ( ) )"
ok("stress_300_sibling_functions", f": {funcs} : print ( {calls} ) ;", str(sum(range(300))))

ps = " ".join(f"#p{k}" for k in range(1, 31))
body = "0"
for k in range(1, 31): body = f"add ( {body} #p{k} )"
args = " ".join(str(k) for k in range(1, 31))
ok("stress_30_parameters", f": num #s ( {ps} ) {{ : : return ( {body} ) }} : print ( #s ( {args} ) ) ;", str(sum(range(1, 31))))

deep = 'print "x" ;'
for _ in range(300): deep = f'if eq ( 1 1 ) then {{ {deep} }} else {{ print "no" ; }} ;'
ok("stress_300_nested_ifs", f": : {deep}", "x")

b = "eq ( 1 1 )"
for _ in range(201): b = f"not ( {b} )"
ok("stress_not_chain_201_is_false", f': : if {b} then {{ print "t" ; }} else {{ print "f" ; }} ;', "f")
b = "eq ( 1 1 )"
for _ in range(200): b = f"not ( {b} )"
ok("stress_not_chain_200_is_true", f': : if {b} then {{ print "t" ; }} else {{ print "f" ; }} ;', "t")

b = "eq ( 1 2 )"
for _ in range(150): b = f"or ( {b} eq ( 1 2 ) )"
b2 = "eq ( 1 1 )"
for _ in range(150): b2 = f"and ( {b2} eq ( 1 1 ) )"
ok("stress_long_or_and_and_chains", f': : if {b} then {{ print "a" ; }} else {{ print "b" ; }} ; if {b2} then {{ print "c" ; }} else {{ print "d" ; }} ;', "bc")

ok("stress_20000_instructions", "#a : : " + " ".join(["#a = add ( #a 1 ) ;"] * 20000) + " print ( #a ) ;", "20000")

# shadowing of the same name at 25 nested levels: every level has its own #x = level number, each prints its own
inner = ""
src = ": : "
def level(k):
    if k == 25: return ": : print ( #x ) ; return"
    return f"#x : void #h{k+1} ( ) {{ " + level(k + 1) + " }" if False else None
# build from the inside: level k body declares #x, assigns k, calls the next level, then prints its own #x again
body = "#x : : #x = 25 ; print ( #x ) ;"
for k in range(24, 0, -1):
    body = f"#x : void #h ( ) {{ {body} return }} : #x = {k} ; #h ( ) ; print ( #x ) ;"
body = body  # outermost P is main
ok("shadowing_25_levels", ": : " if False else body, "".join(str(k) for k in [25] + list(range(24, 0, -1))))
