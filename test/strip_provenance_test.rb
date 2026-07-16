# Covers the case that motivated delete_blank_node_provenance_objects.sparql:
# a prov:wasDerivedFrom triple whose object is a blank node carrying its own
# further ("nested") triples describing that provenance event.
 
require 'minitest/autorun'
require 'linkeddata'
require 'open3'
require 'tmpdir'
require 'fileutils'

class StripProvenanceTest < Minitest::Test
  SCRIPT_PATH = File.expand_path('../scripts/strip_provenance.rb', __dir__)

  PROV = RDF::Vocabulary.new('http://www.w3.org/ns/prov#')
  RDFS = RDF::Vocabulary.new('http://www.w3.org/2000/01/rdf-schema#')
  EX   = RDF::Vocabulary.new('http://example.org/')


  FIXTURE_TTLS = <<~TURTLE
    @prefix : <http://example.org/> .
    @prefix prov: <http://www.w3.org/ns/prov#> .
    @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
    @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

    :resource1 rdfs:label "Resource One" ;
        prov:wasDerivedFrom _:prov1 .

    _:prov1 a prov:Entity ;
        prov:generatedAtTime "2023-01-01T00:00:00Z"^^xsd:dateTime ;
        prov:wasAttributedTo :agent1 ;
        rdfs:label "Nested provenance blank node" .

    :agent1 rdfs:label "Agent One" .
  TURTLE

  def setup
    @dir = Dir.mktmpdir('strip-provenance-test')
    @input_path = File.join(@dir, 'sample.ttls')
    @output_path = File.join(@dir, 'output.ttl')
    File.write(@input_path, FIXTURE_TTLS)
  end

  def teardown
    FileUtils.remove_entry(@dir)
  end

  def test_removes_prov_link_and_all_of_the_nested_blank_nodes_own_triples
    stdout, stderr, status = Open3.capture3('ruby', SCRIPT_PATH, @input_path, @output_path)
    assert status.success?, "script exited non-zero (#{status.exitstatus}):\nSTDOUT:\n#{stdout}\nSTDERR:\n#{stderr}"
    assert File.exist?(@output_path), 'expected output file was not written'

    result = RDF::Graph.load(@output_path)

    # 1. The top-level provenance link itself must be gone.
    refute result.has_statement?(RDF::Statement(EX.resource1, PROV.wasDerivedFrom, nil)),
           'prov:wasDerivedFrom triple should have been removed'

    # 2. No triple anywhere in the output should have a prov: predicate.
    remaining_prov = result.statements.select { |s| s.predicate.to_s.start_with?(PROV.to_s) }
    assert_empty remaining_prov,
                 "expected no prov: predicate triples, found: #{remaining_prov.map(&:to_s)}"

    # 3. The blank node's own nested triples (rdf:type, rdfs:label, and the
    #    prov: ones) must be fully gone
    blank_node_statements = result.statements.select { |s| s.subject.node? }
    assert_empty blank_node_statements,
                 'expected no leftover triples about the provenance blank node ' \
                 "(rdf:type / rdfs:label should be cascade-deleted along with " \
                 "the prov: triples), found: #{blank_node_statements.map(&:to_s)}"

    # 4. Legitimate metadata on real (non-blank) resources must survive
    assert result.has_statement?(RDF::Statement(EX.resource1, RDFS.label, RDF::Literal('Resource One'))),
           "resource1's own label should have been preserved"
    assert result.has_statement?(RDF::Statement(EX.agent1, RDFS.label, RDF::Literal('Agent One'))),
           "agent1's label should have been preserved -- only the blank node's " \
           'own triples should be cascade-deleted, not real resources it merely references'
  end
end
